/* federation.js — admin console client-side logic */

const API_URL = 'api';

let roomFilter = '';

// Tracks which peer sections are manually collapsed across refreshes.
const collapsedPeers = new Set();

// Latest status payload, so per-peer server editors can be built from the peer's exposableServers.
let lastData = {};
// Untrusted peers whose exposed-server editor is expanded (persisted across refreshes).
const expandedPeerRooms = new Set();
// In-flight exposed-server edits per domain (server -> checked) so a background refresh doesn't clobber them.
const editedExposed = {};
// Federated rooms whose settings panel (visibility + mappings + sharing) is expanded (persisted across refreshes).
const expandedRoomVis = new Set();
// In-flight visibility edits per room jid (server -> checked) so a background refresh doesn't clobber them.
const editedRoomVis = {};

// ── Bootstrap ────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    setupTabs();
    bindPeerForm();
    bindRoomDefaultForm();
    bindRoomSearch();
    bindFilters();
    pollLoop();                       // long-poll: renders immediately, then on every change
    setInterval(tickClocks, 1000);    // advances countdowns/probe ages between data updates
});

// ── Tab switching ─────────────────────────────────────────────────────────────

function setupTabs() {
    document.querySelectorAll('.fed-tab').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.fed-tab').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.fed-panel').forEach(p => p.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById('panel-' + btn.dataset.tab).classList.add('active');
        });
    });

    // In-panel sub-tab bars (e.g. Rooms → Room Sharing / Rooms config)
    document.querySelectorAll('.fed-subtab').forEach(btn => {
        btn.addEventListener('click', () => {
            const bar = btn.closest('.fed-subtabs');
            const scope = bar.parentElement;
            bar.querySelectorAll('.fed-subtab').forEach(b => b.classList.remove('active'));
            scope.querySelectorAll('.fed-subpanel').forEach(p => p.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById('subpanel-' + btn.dataset.subtab).classList.add('active');
        });
    });
}

// ── Data refresh ──────────────────────────────────────────────────────────────

// One-shot fetch for immediate feedback right after a POST action; every other update
// arrives through pollLoop() the moment the server-side state changes.
function refresh() {
    fetch(API_URL + '?action=status')
        .then(r => r.json())
        .then(renderAll)
        .catch(err => console.error('Federation API error:', err));
}

// Long-poll loop: the server holds each request open until the status fingerprint
// changes (or ~25 s pass), so the page updates as things happen instead of on a timer.
let statusHash = '';
async function pollLoop() {
    while (true) {
        try {
            const r = await fetch(API_URL + '?action=poll&hash=' + encodeURIComponent(statusHash),
                                  { credentials: 'same-origin' });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const data = await r.json();
            if (data.error) throw new Error(data.error);
            statusHash = data.hash || '';
            renderAll(data);
        } catch (err) {
            // Server restart, expired admin session, network blip — back off and reconnect.
            console.error('Federation poll error:', err);
            await new Promise(res => setTimeout(res, 3000));
        }
    }
}

function renderAll(data) {
    lastData = data;
    if (data.localDomain) {
        const el = document.getElementById('local-domain');
        if (el) el.textContent = data.localDomain;
    }
    const mappings   = data.mappings    || {};
    const localRooms = data.localRooms  || [];
    renderPeers(data.peers || []);
    renderS2SSessions(data.s2sSessions || []);
    renderRouting(data.routing || []);
    renderUsersTab(data.localUsers || [], data.directory || {}, data.advertisedBookmarks || {});
    renderPendingRequests(data.pendingRequests || []);
    renderLocalRooms(localRooms);
    renderRoomDefaults(data.roomDefaults || []);
    renderRemoteRooms(data.remoteRooms || {}, localRooms, mappings);
    updateStatusBadge(data.peers || []);
    updateKeepaliveInput(data.keepaliveSeconds);
    updateReconnectInput(data.reconnectSeconds);
    updateMappingPingInput(data.mappingPingSeconds);
    updateAllowlistToggle(data.peerAllowlist);
    updateTraversalToggle(data.allowRemoteRoomTraversal);
    updateDirectRelayToggle(data.directMsgRelay);
    updateProbeOnSubscribeToggle(data.probeOnSubscribe);
    updateDirectoryPublishToggle(data.directoryPublish);
    updateBookmarkPushToggle(data.bookmarkPush);
    renderFileConfig(data.fileConfig || {});
    applyAllFilters();
}

// ── Status badge ──────────────────────────────────────────────────────────────

function updateStatusBadge(peers) {
    const badge = document.getElementById('fed-status-badge');
    const reachable = peers.filter(p => p.status === 'REACHABLE').length;
    badge.textContent = reachable + '/' + peers.length + ' peers reachable';
    badge.className = 'fed-badge ' + (reachable > 0 ? 'ok' : peers.length === 0 ? 'neutral' : 'warn');
}

// ── Peers tab ─────────────────────────────────────────────────────────────────

function bindPeerForm() {
    const domainField = document.getElementById('input-peer-domain');
    const untrustedCb = document.getElementById('input-peer-untrusted');
    // Default the Untrusted box ON for a foreign parent domain (admin can still override).
    if (domainField && untrustedCb) {
        domainField.addEventListener('input', () => {
            untrustedCb.checked = isForeignDomain(domainField.value.trim());
        });
    }
    document.getElementById('form-add-peer').addEventListener('submit', e => {
        e.preventDefault();
        const domain = domainField.value.trim();
        if (!domain) return;
        const untrusted = untrustedCb ? untrustedCb.checked : false;
        post({ action: 'add-peer', domain, untrusted })
            .then(() => {
                domainField.value = '';
                if (untrustedCb) untrustedCb.checked = false;
                flashSaved('Peer added ✓');
                refresh();
            });
    });
}

/** Parent (registrable) domain = last two DNS labels. Mirrors the server-side default. */
function parentOf(domain) {
    return String(domain || '').toLowerCase().split('.').slice(-2).join('.');
}

/** True if `domain` is under a different parent domain than this server. */
function isForeignDomain(domain) {
    if (!domain || !lastData.localDomain) return false;
    return parentOf(domain) !== parentOf(lastData.localDomain);
}

function renderPeers(peers) {
    const tbody = document.getElementById('peers-tbody');
    if (peers.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="empty">No peers configured yet.</td></tr>';
        return;
    }
    // Snapshot any in-flight server-editor edits BEFORE we rebuild the table, so a poll
    // refresh mid-edit doesn't discard the admin's pending checkbox changes.
    expandedPeerRooms.forEach(domain => captureExposedEdits(domain));

    tbody.innerHTML = peers.map(p => {
        const isWithdrawn      = p.status === 'WITHDRAWN';
        const isUnreachable    = p.status === 'UNREACHABLE';
        const isDisabled       = p.status === 'DISABLED';
        const isRemoteDisabled = p.status === 'REMOTE_DISABLED';
        const isPending        = p.status === 'PENDING';

        const expanded = expandedPeerRooms.has(p.domain);
        const deniedCount = (p.deniedRoutes || []).length;

        let statusLabel;
        if (isWithdrawn)               statusLabel = 'Disconnected by remote';
        else if (isDisabled)           statusLabel = 'Disabled';
        else if (isRemoteDisabled)     statusLabel = 'Disabled by remote';
        else if (isPending)
            statusLabel = 'Pending <span class="retry-countdown">waiting for the remote to add this server as a peer</span>';
        else if (p.status === 'TRUST_MISMATCH')
            statusLabel = 'Trust mismatch — both ends must set the same trust level';
        else                           statusLabel = p.status;
        if (isUnreachable) {
            const retryAt = p.nextRetryAt || 0;
            statusLabel += ` <span class="retry-countdown" data-retry-at="${retryAt}">${retryCountdownText(retryAt)}</span>`;
        }

        const untrustedBadge = p.untrusted
            ? `<span class="badge badge-untrusted" title="Shares only the servers you expose">untrusted</span>`
            : '';

        // S2S cert pinning: a changed cert (possible impersonation) shows a red alert row with
        // the accept button; a quietly-pinned cert shows an unobtrusive lock.
        const certBadge = p.certMismatch
            ? `<span class="badge badge-untrusted" title="The S2S certificate changed since it was first pinned">⚠ cert changed</span>`
            : (p.certPinned ? `<span class="badge badge-in" title="S2S certificate pinned (trust-on-first-use)">🔒 pinned</span>` : '');

        // Compact summary next to the expand arrow (mirrors the rooms list).
        const sumParts = [];
        sumParts.push(p.untrusted ? `${(p.exposedServers || []).length} exposed` : 'trusted');
        if (deniedCount) sumParts.push(`${deniedCount} denied`);
        const dom = jsArg(p.domain);

        let row = `
        <tr>
            <td>${dom}${untrustedBadge}${certBadge}</td>
            <td><span class="status-dot ${statusClass(p.status)}"></span> ${statusLabel}</td>
            <td>${p.lastSeen ? new Date(p.lastSeen).toLocaleString() : '—'}</td>
            <td class="room-detail-cell">
                <span class="room-detail-sum">${sumParts.join(' · ')}</span>
                <button class="room-expand-btn ${expanded ? 'open' : ''}"
                        title="${expanded ? 'Hide' : 'Show'} settings for this peer"
                        onclick="togglePeerRooms('${dom}')">▸</button>
            </td>
        </tr>`;

        if (p.certMismatch) {
            row += `
        <tr class="exposed-editor-row">
            <td colspan="4" style="color:#58151c">
                ⚠ <strong>${dom}</strong> presented a different S2S certificate than the
                one pinned on first contact — this can mean a server was re-created, or an impersonation
                attempt. The peer has been auto-marked untrusted. If you trust the change, click
                <button class="btn-small btn-warn" onclick="acceptCert('${dom}')">Trust new cert</button> —
                otherwise investigate before restoring trust.
            </td>
        </tr>`;
        }

        if (expanded) row += renderPeerDetailRow(p);
        return row;
    }).join('');
}

// ── Per-peer settings panel (connection + trust/exposure + inbound) ───────────

/** Full-width detail row behind the expand arrow: every setting for one peer in one view. */
function renderPeerDetailRow(p) {
    const dom = jsArg(p.domain);
    const id = jidToElemId(p.domain);
    const isWithdrawn = p.status === 'WITHDRAWN';
    const needsRetry = isWithdrawn || p.status === 'UNREACHABLE'
                    || p.status === 'PENDING' || p.status === 'UNKNOWN';

    // ── Connection actions ──
    let actionBtns = '';
    if (p.status === 'DISABLED') {
        // We disabled this peer — only we can re-enable it.
        actionBtns = `<button class="btn-small btn-primary" onclick="enablePeer('${dom}')">Enable</button>`;
    } else if (p.status === 'REMOTE_DISABLED') {
        // Disabled by the remote — cannot be re-enabled from this side.
        actionBtns = '<span class="exposed-col-sub">Disabled by the remote — only they can re-enable it.</span>';
    } else {
        if (needsRetry) {
            actionBtns += `<button class="btn-small btn-warn" onclick="retryPeer('${dom}')">${isWithdrawn ? 'Reconnect' : 'Retry'}</button>`;
        }
        actionBtns += `<button class="btn-small btn-warn" onclick="disablePeer('${dom}')">Disable</button>`;
    }

    // ── Outbound: trust level, and (when untrusted) which servers we expose. ──
    // Pending edits win over the persisted set so a refresh doesn't reset the admin's work.
    const trustBtn = p.untrusted
        ? `<button class="btn-small btn-primary" onclick="setUntrusted('${dom}', false)">Make trusted</button>`
        : `<button class="btn-small btn-warn" onclick="setUntrusted('${dom}', true)">Make untrusted</button>`;
    let outbound;
    if (p.untrusted) {
        const checkedSet = editedExposed[p.domain] || new Set(p.exposedServers || []);
        const candidates = (p.exposableServers || []).slice();
        // Include any exposed server no longer in the candidate list (e.g. route lost) so it's not
        // silently dropped from the UI while still persisted.
        (p.exposedServers || []).forEach(s => { if (!candidates.includes(s)) candidates.push(s); });
        const list = candidates.length === 0
            ? '<p class="empty" style="margin:4px 0">No servers available to expose yet.</p>'
            : candidates.map(s => {
                const checked = checkedSet.has(s) ? 'checked' : '';
                const isLocal = s === lastData.localDomain;
                const tag = isLocal
                    ? '<span class="badge badge-in">this server</span>'
                    : (p.exposableServers || []).includes(s)
                        ? ''
                        : '<span class="badge badge-out" title="no current route">unavailable</span>';
                return `
            <label class="exposed-room">
                <input type="checkbox" class="exposed-cb" data-domain="${dom}"
                       value="${escHtml(s)}" ${checked}
                       onchange="captureExposedEdits('${dom}')">
                <span>${escHtml(s)}</span> ${tag}
            </label>`;
            }).join('');
        outbound = `${list}
            <div style="margin-top:8px">
                <button class="btn-small btn-primary" onclick="saveExposedServers('${dom}')">Save</button>
                <span id="exposed-saved-${id}" style="display:none;color:#28a745;font-size:12px;margin-left:8px">Saved ✓</span>
            </div>`;
    } else {
        outbound = '<p class="empty" style="margin:4px 0">Trusted — this peer sees the full topology and all federated rooms.</p>';
    }

    // ── Inbound: what this peer advertises through to us, each deniable per-link. ──
    const denied = (p.deniedRoutes || []).slice().sort();
    const advSet = new Set([...(p.advertisedVia || []), ...(p.advertisedRoutes || [])]);
    denied.forEach(s => advSet.delete(s));
    const adv = Array.from(advSet).sort();
    const advRows = adv.map(s => `
            <div class="exposed-room"><span>${escHtml(s)}</span>
                <button class="btn-small btn-warn" style="margin-left:8px"
                        title="Refuse this server's routes and rooms when ${dom} advertises them"
                        onclick="denyRoute('${dom}','${jsArg(s)}')">Deny</button>
            </div>`).join('');
    const deniedRows = denied.map(s => `
            <div class="exposed-room"><span style="text-decoration:line-through;color:#999">${escHtml(s)}</span>
                <span class="badge badge-untrusted">denied</span>
                <button class="btn-small btn-primary" style="margin-left:8px"
                        onclick="allowRoute('${dom}','${jsArg(s)}')">Allow</button>
            </div>`).join('');
    const inbound = (adv.length === 0 && denied.length === 0)
        ? '<p class="empty" style="margin:4px 0">This peer is not advertising any other servers to us yet.</p>'
        : advRows + deniedRows;

    return `
        <tr class="exposed-editor-row room-detail-row">
            <td colspan="4">
                <div class="exposed-cols">
                    <div class="exposed-col" style="flex:0 0 200px">
                        <div class="exposed-col-h">Connection
                            <span class="exposed-col-sub">manage the link with ${dom}</span>
                        </div>
                        <div style="display:flex;flex-direction:column;gap:6px;align-items:flex-start">
                            ${actionBtns}
                            <button class="btn-small btn-danger" onclick="removePeer('${dom}')">Remove peer</button>
                        </div>
                    </div>
                    <div class="exposed-col">
                        <div class="exposed-col-h">↑ Exposed to ${dom}
                            <span class="exposed-col-sub">an untrusted peer sees each checked server's federated rooms and a route to it</span>
                        </div>
                        <div style="margin-bottom:8px">${trustBtn}</div>
                        ${outbound}
                    </div>
                    <div class="exposed-col">
                        <div class="exposed-col-h">↓ Advertised by ${dom}
                            <span class="exposed-col-sub">what the remote is exposing inbound — Deny one to refuse its routes and rooms from this peer</span>
                        </div>
                        ${inbound}
                    </div>
                </div>
            </td>
        </tr>`;
}

/** Snapshots the current checkbox state for a peer into editedExposed (survives refresh). */
function captureExposedEdits(domain) {
    const boxes = document.querySelectorAll(`.exposed-cb[data-domain="${cssEscape(domain)}"]`);
    if (boxes.length === 0) return;   // editor not in DOM right now
    const set = new Set();
    boxes.forEach(b => { if (b.checked) set.add(b.value); });
    editedExposed[domain] = set;
}

function togglePeerRooms(domain) {
    if (expandedPeerRooms.has(domain)) {
        captureExposedEdits(domain);
        expandedPeerRooms.delete(domain);
    } else {
        expandedPeerRooms.add(domain);
    }
    renderPeers(lastData.peers || []);
}

function setUntrusted(domain, untrusted) {
    if (untrusted && !confirm('Mark ' + domain + ' as untrusted?\n\n'
        + 'It will immediately stop receiving routing updates and room advertisements. '
        + 'Use the peer\'s settings panel (expand arrow) to choose exactly which servers it may see.')) return;
    post({ action: 'set-untrusted', domain, untrusted }).then(() => {
        if (!untrusted) delete editedExposed[domain];   // exposure list no longer applies
        refresh();
    });
}

function acceptCert(domain) {
    if (!confirm('Trust the new S2S certificate for ' + domain + '?\n\n'
        + 'Only do this if you know the server was legitimately re-created or its certificate '
        + 'was renewed. The new certificate will be pinned and the alert cleared.')) return;
    post({ action: 'accept-cert', domain }).then(refresh);
}

function saveExposedServers(domain) {
    captureExposedEdits(domain);
    const set = editedExposed[domain] || new Set();
    post({ action: 'set-exposed-servers', domain, servers: Array.from(set).join(',') })
        .then(result => {
            if (result && result.ok) {
                delete editedExposed[domain];   // persisted set is now authoritative
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

/** Minimal CSS.escape fallback for attribute selectors (domains are simple, but be safe). */
function cssEscape(s) {
    return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
}

function retryPeer(domain) {
    post({ action: 'retry-peer', domain }).then(refresh);
}

function disablePeer(domain) {
    if (!confirm('Disable federation with ' + domain + '?\n\n'
        + 'This tears down the connection like removing it, but the remote is told '
        + 'and cannot re-enable it from their side — even by deleting and re-adding it. '
        + 'Only you can re-enable it here.')) return;
    post({ action: 'disable-peer', domain }).then(refresh);
}

function enablePeer(domain) {
    post({ action: 'enable-peer', domain }).then(refresh);
}

// ── S2S sessions ──────────────────────────────────────────────────────────────

function renderS2SSessions(sessions) {
    const tbody = document.getElementById('s2s-tbody');
    if (sessions.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="empty">No active S2S sessions.</td></tr>';
        return;
    }

    // Group by domain — one row per peer, show both directions when present.
    const byDomain = {};
    for (const s of sessions) {
        if (!byDomain[s.domain]) byDomain[s.domain] = [];
        byDomain[s.domain].push(s);
    }

    tbody.innerHTML = Object.entries(byDomain).map(([domain, domSessions]) => {
        const hasOut   = domSessions.some(s => s.direction === 'outgoing');
        const hasIn    = domSessions.some(s => s.direction === 'incoming');
        const isFed    = domSessions.some(s => s.fedPeer);
        const isTls    = domSessions.some(s => s.encrypted);
        const earliest = Math.min(...domSessions.map(s => s.since));

        const dirBadges = [
            hasOut ? '<span class="badge badge-out">↑ outgoing</span>' : '',
            hasIn  ? '<span class="badge badge-in">↓ incoming</span>'  : ''
        ].filter(Boolean).join(' ');

        const fedBadge = isFed ? '<span class="badge badge-fed">federation</span>' : '';
        const tlsBadge = isTls
            ? '<span class="badge badge-tls-ok">TLS</span>'
            : '<span class="badge badge-tls">plain</span>';

        const killBtns = domSessions.map(s =>
            `<button class="btn-small btn-danger" style="margin-left:4px"
                     onclick="killSession('${jsArg(domain)}','${jsArg(s.direction)}')">
                 Kill ${s.direction === 'outgoing' ? '↑' : '↓'}
             </button>`
        ).join('');

        // Non-federated S2S session: offer to turn it into a federation peer in one click.
        const addPeerBtn = !isFed
            ? `<button class="btn-small btn-primary"
                       onclick="addPeerFromSession('${jsArg(domain)}')">Add peer</button>`
            : '';

        return `
        <tr>
            <td><strong>${escHtml(domain)}</strong>${fedBadge}</td>
            <td>${dirBadges}</td>
            <td class="ts">${new Date(earliest).toLocaleString()}</td>
            <td>${tlsBadge}</td>
            <td style="white-space:nowrap">${addPeerBtn}${killBtns}</td>
        </tr>`;
    }).join('');
}

/** Adds a peer straight from an active (non-federated) S2S session row. */
function addPeerFromSession(domain) {
    const untrusted = isForeignDomain(domain);
    if (!confirm('Add ' + domain + ' as a federation peer?'
        + (untrusted ? '\n\nIt is under a different parent domain, so it will be added as UNTRUSTED '
                     + '(it sees nothing until you expose servers to it).' : ''))) return;
    post({ action: 'add-peer', domain, untrusted })
        .then(() => { flashSaved('Peer added ✓'); refresh(); });
}

// ── Config file (openfire.xml) ──────────────────────────────────────────────────

function renderFileConfig(fc) {
    const pathEl = document.getElementById('fedconfig-path');
    if (pathEl) pathEl.textContent = fc.path || '';

    const loadedEl = document.getElementById('fedconfig-loaded');
    if (loadedEl) loadedEl.textContent = fc.loadedAt ? new Date(fc.loadedAt).toLocaleString() : 'never';

    const summaryEl = document.getElementById('fedconfig-summary');
    if (summaryEl) {
        summaryEl.textContent = fc.blockPresent
            ? ('+' + (fc.peersAdded || 0) + ' peer(s) added, ' + (fc.peersUpdated || 0)
               + ' peer field(s) updated, ' + (fc.roomsUpdated || 0) + ' room(s) updated, '
               + (fc.mappingsRequested || 0) + ' mapping(s) requested.')
            : 'No <federation> block found in openfire.xml — nothing to ingest.';
    }

    const warnEl = document.getElementById('fedconfig-warnings');
    if (warnEl) {
        const warnings = fc.warnings || [];
        warnEl.innerHTML = warnings.map(w => '<div class="fedconfig-warn">⚠ ' + escHtml(w) + '</div>').join('');
    }
}

function reloadFedConfig() {
    post({ action: 'reload-fed-config' })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Reloaded: +' + result.peersAdded + ' peer(s), ' + result.roomsUpdated
                          + ' room(s), ' + result.mappingsRequested + ' mapping(s)');
                refresh();
            } else if (result && result.error) {
                alert(result.error);
            }
        });
}

// ── Connection settings ───────────────────────────────────────────────────────

function updateKeepaliveInput(seconds) {
    const inp = document.getElementById('keepalive-input');
    // Don't overwrite while the user is actively editing the field.
    if (inp && document.activeElement !== inp) {
        inp.value = seconds != null ? seconds : 240;
    }
}

function saveKeepalive() {
    const inp = document.getElementById('keepalive-input');
    const seconds = parseInt(inp ? inp.value : '', 10);
    if (isNaN(seconds) || seconds < 30) {
        alert('Keepalive interval must be at least 30 seconds.');
        return;
    }
    post({ action: 'set-keepalive', seconds })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

function updateReconnectInput(seconds) {
    const inp = document.getElementById('reconnect-input');
    if (inp && document.activeElement !== inp) {
        inp.value = seconds != null ? seconds : 30;
    }
}

function saveReconnect() {
    const inp = document.getElementById('reconnect-input');
    const seconds = parseInt(inp ? inp.value : '', 10);
    if (isNaN(seconds) || seconds < 5) {
        alert('Reconnect interval must be at least 5 seconds.');
        return;
    }
    post({ action: 'set-reconnect', seconds })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

function updateMappingPingInput(seconds) {
    const inp = document.getElementById('mapping-ping-input');
    if (inp && document.activeElement !== inp) {
        inp.value = seconds != null ? seconds : 30;
    }
}

function saveMappingPing() {
    const inp = document.getElementById('mapping-ping-input');
    const seconds = parseInt(inp ? inp.value : '', 10);
    if (isNaN(seconds) || seconds < 0 || (seconds > 0 && seconds < 15)) {
        alert('Mapping probe interval must be 0 (probe off) or at least 15 seconds.');
        return;
    }
    post({ action: 'set-mapping-ping', seconds })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

function killSession(domain, direction) {
    if (!confirm('Close ' + direction + ' S2S session with ' + domain + '?')) return;
    post({ action: 'kill-session', domain, direction }).then(refresh);
}

// ── Security: peer allowlist toggle ────────────────────────────────────────────

function updateAllowlistToggle(enabled) {
    const cb = document.getElementById('allowlist-toggle');
    const lbl = document.getElementById('allowlist-state');
    if (cb && document.activeElement !== cb) cb.checked = !!enabled;
    if (lbl) lbl.textContent = enabled ? 'Approved peers only' : 'Open federation';
}

function saveAllowlist() {
    const cb = document.getElementById('allowlist-toggle');
    if (!cb) return;
    const enabled = cb.checked;
    if (enabled && !confirm(
        'Restrict federation to approved peers?\n\n' +
        'Only peers added on this page will be able to federate; any other server is rejected. ' +
        'Your existing peers stay approved.')) {
        cb.checked = false;
        return;
    }
    post({ action: 'set-allowlist', enabled })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

// ── Security: remote-room traversal (allow direct S2S room access) toggle ───────

function updateTraversalToggle(enabled) {
    const cb = document.getElementById('traversal-toggle');
    const lbl = document.getElementById('traversal-state');
    if (cb && document.activeElement !== cb) cb.checked = !!enabled;
    if (lbl) lbl.textContent = enabled ? 'Remote rooms reachable' : 'Mapping required';
}

function saveTraversal() {
    const cb = document.getElementById('traversal-toggle');
    if (!cb) return;
    const enabled = cb.checked;
    post({ action: 'set-allow-traversal', enabled })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

// ── Security: 1:1 message relay toggle ──────────────────────────────────────────

function updateDirectRelayToggle(enabled) {
    const cb = document.getElementById('directrelay-toggle');
    const lbl = document.getElementById('directrelay-state');
    if (cb && document.activeElement !== cb) cb.checked = !!enabled;
    if (lbl) lbl.textContent = enabled ? 'Relayed over federation' : 'Native S2S';
}

function saveDirectRelay() {
    const cb = document.getElementById('directrelay-toggle');
    if (!cb) return;
    const enabled = cb.checked;
    post({ action: 'set-direct-relay', enabled })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

// ── Presence: probe federated contacts on subscription ──────────────────────────

function updateProbeOnSubscribeToggle(enabled) {
    const cb = document.getElementById('probesub-toggle');
    const lbl = document.getElementById('probesub-state');
    if (cb && document.activeElement !== cb) cb.checked = !!enabled;
    if (lbl) lbl.textContent = enabled ? 'Probed over federation' : 'Native S2S';
}

function saveProbeOnSubscribe() {
    const cb = document.getElementById('probesub-toggle');
    if (!cb) return;
    const enabled = cb.checked;
    post({ action: 'set-probe-on-subscribe', enabled })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

// ── Security: publish user directory toggle ─────────────────────────────────────

function updateDirectoryPublishToggle(enabled) {
    const cb = document.getElementById('dirpublish-toggle');
    const lbl = document.getElementById('dirpublish-state');
    if (cb && document.activeElement !== cb) cb.checked = !!enabled;
    if (lbl) lbl.textContent = enabled ? 'Published to peers' : 'Not published';
}

function saveDirectoryPublish() {
    const cb = document.getElementById('dirpublish-toggle');
    if (!cb) return;
    const enabled = cb.checked;
    post({ action: 'set-directory-publish', enabled })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

function removePeer(domain) {
    if (!confirm('Remove peer ' + domain + '?')) return;
    post({ action: 'remove-peer', domain }).then(refresh);
}

function statusClass(s) {
    if (s === 'REACHABLE')  return 'green';
    if (s === 'UNREACHABLE') return 'red';
    if (s === 'PENDING')     return 'orange';
    if (s === 'WITHDRAWN')   return 'orange';
    if (s === 'TRUST_MISMATCH') return 'orange';
    if (s === 'DISABLED' || s === 'REMOTE_DISABLED') return 'red';
    return 'grey';
}

// ── Routing table tab ─────────────────────────────────────────────────────────

function renderRouting(entries) {
    const tbody = document.getElementById('routing-tbody');
    if (entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="empty">No routes yet — waiting for S2S connections.</td></tr>';
        return;
    }
    // Active routes by hop count first; denied (disabled) entries at the bottom.
    entries.sort((a, b) => (a.denied ? 1 : 0) - (b.denied ? 1 : 0) || (a.hops || 0) - (b.hops || 0));
    tbody.innerHTML = entries.map(r => {
        if (r.denied) {
            // A deny is remembered per (peer, destination) — the entry stays here disabled
            // even if the peer withdraws or re-advertises the route, until it is allowed again.
            return `
        <tr class="route-denied">
            <td><s>${escHtml(r.destination)}</s></td>
            <td>${escHtml(r.nextHop)}</td>
            <td>—</td>
            <td><span class="badge badge-untrusted" title="Advertisements of this destination from ${escHtml(r.nextHop)} are refused — even if it is withdrawn and offered again">denied</span></td>
            <td><button class="btn-small btn-primary"
                        onclick="allowRoute('${jsArg(r.nextHop)}','${jsArg(r.destination)}')">Allow</button></td>
        </tr>`;
        }
        // A learned (indirect) route was advertised to us by its next hop — the admin can
        // deny that advertisement. A direct route IS the peer; use Disable/Remove instead.
        const denyBtn = r.destination !== r.nextHop
            ? `<button class="btn-small btn-warn"
                       onclick="denyRoute('${jsArg(r.nextHop)}','${jsArg(r.destination)}')">Deny</button>`
            : '';
        return `
        <tr>
            <td>${escHtml(r.destination)}</td>
            <td>${escHtml(r.nextHop)}</td>
            <td>${r.hops}</td>
            <td class="ts">${new Date(r.updatedAt).toLocaleTimeString()}</td>
            <td>${denyBtn}</td>
        </tr>`;
    }).join('');
}

/** Denies a destination's route/room advertisements arriving from a specific peer. */
function denyRoute(peerDomain, destination) {
    if (!confirm('Deny the route to ' + destination + ' advertised by ' + peerDomain + '?\n\n'
        + 'This server will refuse that destination (and its rooms) whenever ' + peerDomain
        + ' advertises it — the entry stays in this table as disabled, and the deny is '
        + 'remembered even if the route is withdrawn and comes back. A route to the same '
        + 'destination via another peer is not affected. Use Allow to lift it.')) return;
    post({ action: 'deny-route', domain: peerDomain, destination })
        .then(result => {
            if (result && result.error) alert(result.error);
            else flashSaved('Route denied ✓');
            refresh();
        });
}

/** Lifts a route deny; the peer is asked to re-advertise so the route re-appears. */
function allowRoute(peerDomain, destination) {
    post({ action: 'allow-route', domain: peerDomain, destination })
        .then(result => {
            if (result && result.error) alert(result.error);
            else flashSaved('Route allowed ✓');
            refresh();
        });
}

// ── Users tab ──────────────────────────────────────────────────────────────────

function renderUsersTab(localUsers, directory, advertisedBookmarks) {
    advertisedBookmarks = advertisedBookmarks || {};
    // This server's connected clients.
    const tbody = document.getElementById('local-users-tbody');
    if (tbody) {
        tbody.innerHTML = localUsers.length === 0
            ? '<tr><td colspan="2" class="empty">No clients connected to this server.</td></tr>'
            : localUsers.map(u => {
                const st = u.status ? ` <span style="color:#888">${escHtml(u.status)}</span>` : '';
                return `
                <tr>
                    <td style="font-family:monospace;font-size:12px">${escHtml(u.jid)}</td>
                    <td>${presenceDot(u.show)}${u.show ? escHtml(u.show) : 'available'}${st}</td>
                </tr>`;
            }).join('');
    }

    // Connected clients each peer has advertised to us as XEP-0048 bookmarks (injected into our
    // local users' bookmark storage), grouped by origin server.
    const bm = document.getElementById('advertised-bookmarks-container');
    if (bm) {
        const bmServers = Object.keys(advertisedBookmarks).filter(s => (advertisedBookmarks[s] || []).length > 0).sort();
        bm.innerHTML = bmServers.length === 0
            ? '<p class="empty">No peers are advertising bookmarks yet. A peer must enable bookmark '
              + 'auto-push (or click "Push bookmarks to peers") for its connected clients to appear here. '
              + 'These are injected into your local users\' bookmark storage as <code>&lt;url&gt;</code> bookmarks.</p>'
            : bmServers.map(srv => {
                const jids = advertisedBookmarks[srv] || [];
                const items = jids.map(j =>
                    `<span style="display:inline-block;margin:2px 14px 2px 0;font-family:monospace;font-size:12px">${escHtml(j)}</span>`
                ).join('');
                return `
                <div class="peer-section">
                    <div class="peer-section-header" style="cursor:default">
                        <strong>${escHtml(srv)}</strong>
                        <span class="peer-room-count">${jids.length} client(s)</span>
                    </div>
                    <div class="peer-section-body" style="padding:10px 14px">${items}</div>
                </div>`;
            }).join('');
    }

    // Users advertised by peer servers (the directory gossip), grouped by origin server.
    const container = document.getElementById('remote-users-container');
    if (!container) return;
    const servers = Object.keys(directory).filter(s => (directory[s] || []).length > 0).sort();
    if (servers.length === 0) {
        container.innerHTML = '<p class="empty">No peers are publishing their users yet. A peer must enable '
            + '"Publish my user directory to peers" for its online users to appear here.</p>';
        return;
    }
    container.innerHTML = servers.map(srv => {
        const users = directory[srv] || [];
        const items = users.map(u => {
            const st = u.status ? ` <span style="color:#888">(${escHtml(u.status)})</span>` : '';
            return `<span style="display:inline-block;margin:2px 14px 2px 0;font-family:monospace;font-size:12px">`
                 + `${presenceDot(u.show)}${escHtml(u.jid)}${st}</span>`;
        }).join('');
        return `
        <div class="peer-section">
            <div class="peer-section-header" style="cursor:default">
                <strong>${escHtml(srv)}</strong>
                <span class="peer-room-count">${users.length} user(s)</span>
            </div>
            <div class="peer-section-body" style="padding:10px 14px">${items}</div>
        </div>`;
    }).join('');
}

// ── Users tab: bookmark push controls ──────────────────────────────────────────

function updateBookmarkPushToggle(enabled) {
    const cb = document.getElementById('bookmarkpush-toggle');
    const lbl = document.getElementById('bookmarkpush-state');
    if (cb && document.activeElement !== cb) cb.checked = !!enabled;
    if (lbl) lbl.textContent = enabled ? 'Advertised to peers' : 'Not advertised';
}

function saveBookmarkPush() {
    const cb = document.getElementById('bookmarkpush-toggle');
    if (!cb) return;
    const enabled = cb.checked;
    post({ action: 'set-bookmark-push', enabled })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

function pushBookmarks() {
    post({ action: 'push-bookmarks' })
        .then(result => {
            if (result && result.ok) {
                flashSaved('Pushed ✓');
            }
        });
}

// ── Room default-settings rules (Rooms tab) ────────────────────────────────────

function renderRoomDefaults(rules) {
    const tbody = document.getElementById('room-defaults-tbody');
    if (!tbody) return;
    if (!rules.length) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty">No rules — new rooms get no automatic federation settings.</td></tr>';
        return;
    }
    // Server provides rules most-specific first (evaluation order); keep that order.
    tbody.innerHTML = rules.map(r => {
        const vis = (r.visible && r.visible.length)
            ? (r.visible.includes('*') ? 'all peers' : r.visible.join(', '))
            : '—';
        const yn = v => v ? '✓' : '—';
        const p = escHtml(r.pattern);
        const pj = jsArg(r.pattern);
        return `<tr>
            <td><code>${p}</code></td>
            <td>${yn(r.federated)}</td>
            <td>${yn(r.autoAccept)}</td>
            <td>${escHtml(vis)}</td>
            <td>${yn(r.autoMap)}</td>
            <td style="text-align:right">
                <button class="btn btn-small" onclick="editRoomDefault('${pj}')">Edit</button>
                <button class="btn btn-small btn-danger" onclick="deleteRoomDefault('${pj}')">Delete</button>
            </td>
        </tr>`;
    }).join('');
}

function bindRoomDefaultForm() {
    const form = document.getElementById('form-room-default');
    if (!form) return;
    form.addEventListener('submit', e => {
        e.preventDefault();
        const pattern = document.getElementById('rd-pattern').value.trim();
        if (!pattern) return;
        post({
            action:     'save-room-default',
            pattern,
            federated:  document.getElementById('rd-federated').checked,
            autoAccept: document.getElementById('rd-autoaccept').checked,
            autoMap:    document.getElementById('rd-automap').checked,
            visible:    document.getElementById('rd-visible').value.trim()
        }).then(result => {
            if (result && result.ok) {
                document.getElementById('rd-pattern').value = '';
                document.getElementById('rd-visible').value = '';
                document.getElementById('rd-federated').checked = true;
                document.getElementById('rd-autoaccept').checked = false;
                document.getElementById('rd-automap').checked = false;
                flashSaved('Rule saved ✓');
                refresh();
            } else if (result && result.error) {
                alert(result.error);
            }
        });
    });
}

// Load an existing rule back into the form for editing (saving re-upserts by pattern).
function editRoomDefault(pattern) {
    const r = (lastData.roomDefaults || []).find(x => x.pattern === pattern);
    if (!r) return;
    document.getElementById('rd-pattern').value = r.pattern;
    document.getElementById('rd-federated').checked = !!r.federated;
    document.getElementById('rd-autoaccept').checked = !!r.autoAccept;
    document.getElementById('rd-automap').checked = !!r.autoMap;
    document.getElementById('rd-visible').value =
        (r.visible && r.visible.length) ? r.visible.join(', ') : '';
    document.getElementById('rd-pattern').focus();
}

function deleteRoomDefault(pattern) {
    if (!confirm('Delete the rule for "' + pattern + '"?')) return;
    post({ action: 'delete-room-default', pattern }).then(result => {
        if (result && result.ok) { flashSaved('Rule deleted ✓'); refresh(); }
    });
}

function applyRoomDefaultsNow() {
    if (!confirm('Apply the default-settings rules to every existing room now?')) return;
    post({ action: 'apply-room-defaults-now' }).then(result => {
        if (result && result.ok) {
            flashSaved('Applied to ' + (result.applied || 0) + ' room(s) ✓');
            refresh();
        }
    });
}

// A small colored presence dot. show: '' (available), away/xa, dnd, chat; 'offline' = grey.
function presenceDot(show) {
    let color = '#28a745';                                   // available / chat
    if (show === 'away' || show === 'xa') color = '#f0ad4e'; // away
    else if (show === 'dnd')              color = '#dc3545'; // do not disturb
    else if (show === 'offline')          color = '#adb5bd'; // offline
    const title = show ? show : 'available';
    return `<span title="${title}" style="display:inline-block;width:8px;height:8px;border-radius:50%;`
         + `background:${color};margin-right:5px;vertical-align:middle"></span>`;
}

// ── Live clock text ───────────────────────────────────────────────────────────
//
// The page only re-renders when the data actually changes, so text derived from the
// current time (retry countdowns, probe ages) is advanced here once a second from the
// absolute timestamps stamped on the elements at render time.

function retryCountdownText(retryAt) {
    const left = retryAt - Date.now();
    return left > 0 ? `next retry in ${Math.ceil(left / 1000)}s` : 'retrying…';
}

function pingAgeText(secs) {
    return secs < 120 ? `${secs}s ago` : `${Math.round(secs / 60)}m ago`;
}

function tickClocks() {
    document.querySelectorAll('.retry-countdown[data-retry-at]').forEach(el => {
        el.textContent = retryCountdownText(parseInt(el.dataset.retryAt, 10));
    });
    document.querySelectorAll('.mapping-ping[data-pong-at]').forEach(el => {
        const secs = Math.max(0, Math.round((Date.now() - parseInt(el.dataset.pongAt, 10)) / 1000));
        el.textContent = `ping ${el.dataset.rtt} · ${pingAgeText(secs)}`;
    });
}

// ── Room search ───────────────────────────────────────────────────────────────

function bindRoomSearch() {
    document.getElementById('room-search').addEventListener('input', e => {
        roomFilter = e.target.value.toLowerCase();
        // Detail rows (settings panel, occupants) carry data-parent-jid and follow their room row.
        document.querySelectorAll('#local-rooms-tbody tr[data-jid], #local-rooms-tbody tr[data-parent-jid]').forEach(row => {
            const jid = row.dataset.jid || row.dataset.parentJid;
            row.style.display = jid.toLowerCase().includes(roomFilter) ? '' : 'none';
        });
    });
}

// ── Generic list filtering + live counts ──────────────────────────────────────
//
// Any `.filter-input[data-target]` filters the rows of the table tbody (or the
// `.peer-section` cards of the container) with that id by substring match, and any
// `.count-chip[data-count-for]` shows that list's size. Both survive background updates:
// renders rebuild the rows, then renderAll() calls applyAllFilters() to re-apply.
const tableFilters = {};   // targetId -> active lowercase query (presence = it has a filter input)

function bindFilters() {
    document.querySelectorAll('.filter-input').forEach(inp => {
        const target = inp.dataset.target;
        tableFilters[target] = '';
        inp.addEventListener('input', () => {
            tableFilters[target] = inp.value.trim().toLowerCase();
            applyAllFilters();
        });
    });
}

/** Re-applies every active filter and refreshes every count chip. Cheap; safe to call each poll. */
function applyAllFilters() {
    document.querySelectorAll('[data-count-for]').forEach(chip => {
        const target = chip.dataset.countFor;
        // null query = count-only (no filter input owns this list, e.g. local rooms);
        // a string (even '') = this list is filterable, so set row/section visibility too.
        const q = (target in tableFilters) ? tableFilters[target] : null;
        const el = document.getElementById(target);
        if (!el) { chip.textContent = ''; return; }
        const { shown, total } = (el.tagName === 'TBODY') ? filterTbody(el, q) : filterContainer(el, q);
        chip.textContent = (q && q.length) ? shown + '/' + total : String(total);
    });
    // Filterable lists that have no count chip still need their visibility applied.
    document.querySelectorAll('.filter-input').forEach(inp => {
        const target = inp.dataset.target;
        if (document.querySelector('[data-count-for="' + cssEscape(target) + '"]')) return;
        const el = document.getElementById(target);
        if (!el) return;
        (el.tagName === 'TBODY' ? filterTbody : filterContainer)(el, tableFilters[target] || '');
    });
}

/** Filters top-level rows of a tbody; detail rows (editors/occupants) follow their parent. */
function filterTbody(tbody, q) {
    let shown = 0, total = 0, lastVisible = true;
    tbody.querySelectorAll(':scope > tr').forEach(tr => {
        if (tr.querySelector('td.empty')) return;   // placeholder — leave untouched
        const isDetail = tr.classList.contains('exposed-editor-row')
                      || tr.classList.contains('route-detail');
        if (isDetail) {
            if (q != null) tr.style.display = lastVisible ? '' : 'none';
            return;
        }
        total++;
        const match = (q == null) || !q || tr.textContent.toLowerCase().includes(q);
        lastVisible = match;
        if (q != null) tr.style.display = match ? '' : 'none';
        if (match) shown++;
    });
    return { shown, total };
}

/** Filters the `.peer-section` cards of a container by substring match. */
function filterContainer(el, q) {
    let shown = 0, total = 0;
    el.querySelectorAll(':scope > .peer-section').forEach(sec => {
        total++;
        const match = (q == null) || !q || sec.textContent.toLowerCase().includes(q);
        if (q != null) sec.style.display = match ? '' : 'none';
        if (match) shown++;
    });
    return { shown, total };
}

// ── Local rooms ───────────────────────────────────────────────────────────────

function renderLocalRooms(rooms) {
    const tbody = document.getElementById('local-rooms-tbody');
    if (rooms.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="empty">No local MUC rooms found.</td></tr>';
        return;
    }
    // Snapshot in-flight visibility edits before rebuilding so a poll mid-edit isn't lost.
    expandedRoomVis.forEach(jid => captureRoomVisEdits(jid));

    tbody.innerHTML = rooms.map(r => {
        const expanded = r.federated && expandedRoomVis.has(r.jid);
        const visible = r.jid.toLowerCase().includes(roomFilter) ? '' : 'display:none';
        // Occupant roster expander (local + remote virtual occupants, with live presence).
        const occList = r.occupantList || [];
        const occExpanded = expandedRoomOccupants.has(r.jid);
        const occCell = occList.length > 0
            ? `<span style="cursor:pointer" title="show occupants" onclick="toggleRoomOccupants('${jsArg(r.jid)}')">${occList.length} ${occExpanded ? '▾' : '▸'}</span>`
            : `${r.occupants}`;
        // Once federated, all per-room settings live behind the expand arrow on the right.
        let settingsCell = '';
        if (r.federated) {
            const visList = r.visibleTo || [];
            const visLabel = visList.includes('*') ? 'all peers'
                           : (visList.length ? visList.length + ' server(s)' : 'nobody');
            const mapCount = (r.mappings || []).length;
            settingsCell = `
                <span class="room-detail-sum">${mapCount ? mapCount + ' mapped' : 'not mapped'} · visible: ${visLabel}</span>
                <button class="room-expand-btn ${expanded ? 'open' : ''}"
                        title="${expanded ? 'Hide' : 'Show'} federation settings for this room"
                        onclick="toggleRoomDetail('${jsArg(r.jid)}')">▸</button>`;
        }
        let row = `
        <tr data-jid="${escHtml(r.jid)}" style="${visible}">
            <td><strong>${escHtml(r.name || r.jid)}</strong><br><small>${escHtml(r.jid)}</small></td>
            <td>${escHtml(r.description || '—')}</td>
            <td>${occCell}</td>
            <td>
                <label class="toggle">
                    <input type="checkbox" ${r.federated ? 'checked' : ''}
                           onchange="setRoomFederated('${jsArg(r.jid)}', this.checked)">
                    <span class="slider"></span>
                </label>
            </td>
            <td class="room-detail-cell">${settingsCell}</td>
        </tr>`;
        if (expanded) row += renderRoomDetailRow(r);
        if (occExpanded && occList.length > 0) row += renderRoomOccupants(r);
        return row;
    }).join('');
}

let expandedRoomOccupants = new Set();

function toggleRoomOccupants(jid) {
    if (expandedRoomOccupants.has(jid)) expandedRoomOccupants.delete(jid);
    else expandedRoomOccupants.add(jid);
    refresh();
}

function renderRoomOccupants(r) {
    const occ = r.occupantList || [];
    const items = occ.map(o => {
        const kind = o.kind === 'remote'
            ? '<span style="color:#0d6efd;font-size:10px;margin-left:4px">remote</span>'
            : '<span style="color:#6c757d;font-size:10px;margin-left:4px">local</span>';
        const st = o.status ? ` <span style="color:#888">(${escHtml(o.status)})</span>` : '';
        return `<span style="display:inline-block;margin:2px 14px 2px 0;font-family:monospace;font-size:12px">`
             + `${presenceDot(o.show)}${escHtml(o.name)}${kind}${st}</span>`;
    }).join('') || '<span style="color:#999">no occupants</span>';
    return `<tr class="route-detail" data-parent-jid="${escHtml(r.jid)}"><td colspan="5" style="background:#fafafa;padding:8px 24px">
        <div style="font-size:12px;color:#555;margin-bottom:4px">Occupants in ${escHtml(r.name || r.jid)} (local + federated):</div>
        ${items}
    </td></tr>`;
}

function setRoomFederated(jid, federated) {
    post({ action: 'set-room', jid, federated: federated.toString() })
        .then(() => { flashSaved('Saved ✓'); refresh(); });
}

function setRoomAutoAccept(jid, autoAccept) {
    post({ action: 'set-room-autoaccept', jid, autoAccept: autoAccept.toString() })
        .then(() => { flashSaved('Saved ✓'); refresh(); });
}

// ── Mapping consent: inline rows + pending-requests panel ───────────────────────

/** "12 ms · 3s ago" chip for the end-to-end mapping probe; explains itself when no pong yet. */
function renderMappingPing(m, dom) {
    if (m.state !== 'ACTIVE') return '';
    if (m.pongAgeSecs >= 0) {
        const pongAt = Date.now() - m.pongAgeSecs * 1000;
        const age = pingAgeText(m.pongAgeSecs);
        const rtt = m.pingMs >= 0 ? `${m.pingMs} ms` : '✓';
        return `<span class="mapping-ping" data-pong-at="${pongAt}" data-rtt="${rtt}" title="End-to-end probe of the full path to ${dom} — last answer ${age}${m.pingMs >= 0 ? `, round trip ${m.pingMs} ms` : ''}">ping ${rtt} · ${age}</span>`;
    }
    if (m.connected !== false) {
        return `<span class="mapping-ping" title="No end-to-end probe answer from ${dom} since this server started — the probe runs every minute; a persistent blank here can mean the reverse path is broken or the peer predates 1.7.19">ping —</span>`;
    }
    return '';
}

/** Renders one mapping row with a state badge and the buttons valid for that state. */
function renderMappingRow(localJid, m) {
    const dom = jsArg(m.remoteDomain);
    const lj = jsArg(localJid);
    const target = `<span class="badge badge-fed" title="${dom}">${escHtml(m.remoteRoomJid)}</span>`;
    const unmap = `<button class="btn-small btn-danger" onclick="unmapRoom('${lj}','${dom}')">Unmap</button>`;
    let badge, buttons;
    switch (m.state) {
        case 'ACTIVE':
            badge = (m.connected === false)
                ? (m.pathBroken
                    ? `<span class="mapping-state mapping-disconnected" title="A route to ${dom} exists but it is not answering end-to-end — a server on the path may be denying this traffic">⚠ not responding</span>`
                    : m.routeMissing
                    ? `<span class="mapping-state mapping-disconnected" title="No route to ${dom} — an intermediate peer is not advertising it">⚠ route missing</span>`
                    : `<span class="mapping-state mapping-disconnected" title="Route to ${dom} is down">⚠ disconnected</span>`)
                : `<span class="mapping-state mapping-connected">● active</span>`;
            badge += ` ${renderMappingPing(m, dom)}`;
            buttons = `<button class="btn-small btn-warn" onclick="mappingAction('disable-mapping','${lj}','${dom}')">Disable</button> ${unmap}`;
            break;
        case 'PENDING_OUT':
            badge = `<span class="mapping-state" style="color:#856404">⧗ awaiting accept</span>`;
            buttons = `<button class="btn-small" style="background:#e2e3e5;color:#383d41" onclick="mappingAction('','${lj}','${dom}',true)">Re-request</button> ${unmap}`;
            break;
        case 'PENDING_IN':
            badge = `<span class="mapping-state" style="color:#856404">⧗ awaiting your approval</span>`;
            buttons = `<button class="btn-small btn-primary" onclick="mappingAction('accept-mapping','${lj}','${dom}')">Accept</button>
                       <button class="btn-small btn-warn" onclick="mappingAction('reject-mapping','${lj}','${dom}')">Reject</button>`;
            break;
        case 'DISABLED_LOCAL':
            badge = `<span class="mapping-state" style="color:#6c757d">⛔ disabled (by you)</span>`;
            buttons = `<button class="btn-small btn-primary" onclick="mappingAction('enable-mapping','${lj}','${dom}')">Enable</button> ${unmap}`;
            break;
        case 'DISABLED_REMOTE':
            badge = `<span class="mapping-state" style="color:#6c757d">⛔ disabled by peer</span>`;
            buttons = unmap;
            break;
        case 'REJECTED':
            badge = `<span class="mapping-state mapping-disconnected">✖ rejected</span>`;
            buttons = `<button class="btn-small" style="background:#e2e3e5;color:#383d41" onclick="mappingAction('','${lj}','${dom}',true)">Re-request</button> ${unmap}`;
            break;
        default:
            badge = `<span class="mapping-state">${escHtml(m.state || '')}</span>`;
            buttons = unmap;
    }
    return `<div class="mapping-row">${target} ${badge} ${buttons}</div>`;
}

/** Generic mapping lifecycle action. When reRequest is true, re-sends the original map request. */
function mappingAction(action, localJid, remoteDomain, reRequest) {
    if (reRequest) {
        // Re-issue the request using the existing mapping's remote room (looked up from lastData).
        const room = (lastData.localRooms || []).find(x => x.jid === localJid);
        const m = room && (room.mappings || []).find(x => x.remoteDomain === remoteDomain);
        if (!m) return;
        post({ action: 'map-room', localJid, remoteJid: m.remoteRoomJid, remoteDomain })
            .then(d => { if (d && d.error) alert(d.error); refresh(); });
        return;
    }
    post({ action, localJid, remoteDomain }).then(refresh);
}

function renderPendingRequests(reqs) {
    const box = document.getElementById('pending-requests-box');
    const body = document.getElementById('pending-requests');
    const badge = document.getElementById('rooms-tab-badge');
    if (!box || !body) return;
    if (!reqs.length) {
        box.style.display = 'none';
        if (badge) badge.style.display = 'none';
        body.innerHTML = '';
        return;
    }
    box.style.display = '';
    if (badge) { badge.style.display = ''; badge.textContent = reqs.length; }
    body.innerHTML = reqs.map(req => `
        <div class="mapping-row" style="padding:6px 0;border-bottom:1px solid #f0f0f0">
            <strong>${escHtml(req.remoteDomain)}</strong> wants to map
            <span class="badge badge-fed">${escHtml(req.remoteRoomJid)}</span> onto your
            <span class="badge badge-fed">${escHtml(req.localJid)}</span>
            <button class="btn-small btn-primary" onclick="mappingAction('accept-mapping','${jsArg(req.localJid)}','${jsArg(req.remoteDomain)}')">Accept</button>
            <button class="btn-small btn-warn" onclick="mappingAction('reject-mapping','${jsArg(req.localJid)}','${jsArg(req.remoteDomain)}')">Reject</button>
        </div>`).join('');
}

// ── Per-room federation settings panel (sharing + visibility + mappings) ──────

/** Full-width detail row behind the expand arrow: every federation setting for one room in one view. */
function renderRoomDetailRow(r) {
    const jid = jsArg(r.jid);
    const mappings = (r.mappings && r.mappings.length > 0)
        ? r.mappings.map(m => renderMappingRow(r.jid, m)).join('')
        : '<p class="empty" style="margin:6px 0">Not mapped yet — pick this room next to a remote room in the “Remote rooms” list below.</p>';
    return `
    <tr class="exposed-editor-row room-detail-row" data-parent-jid="${jid}">
        <td colspan="5">
            <div class="exposed-cols">
                <div class="exposed-col" style="flex:0 0 230px">
                    <div class="exposed-col-h">Sharing
                        <span class="exposed-col-sub">how ${escHtml(r.name || r.jid)} handles incoming requests</span>
                    </div>
                    <label class="exposed-room">
                        <input type="checkbox" ${r.autoAccept ? 'checked' : ''}
                               onchange="setRoomAutoAccept('${jid}', this.checked)">
                        <span>Auto-accept mapping requests</span>
                    </label>
                </div>
                <div class="exposed-col">
                    <div class="exposed-col-h">Visibility
                        <span class="exposed-col-sub">none selected = visible to nobody; a checked server with no route yet is pending</span>
                    </div>
                    ${renderRoomVisSection(r)}
                </div>
                <div class="exposed-col">
                    <div class="exposed-col-h">Mappings
                        <span class="exposed-col-sub">remote rooms bridged to this one</span>
                    </div>
                    ${mappings}
                </div>
            </div>
        </td>
    </tr>`;
}

/** The visibility ACL editor section (used inside the room settings panel). */
function renderRoomVisSection(r) {
    const id = jidToElemId(r.jid);
    const curSet = editedRoomVis[r.jid] || new Set(r.visibleTo || []);
    const allOn = curSet.has('*');

    let body;
    if (allOn) {
        body = '<p class="empty" style="margin:6px 0">Visible to every peer.</p>';
    } else {
        // Candidates = routable destinations ∪ ACL ∪ in-flight checked (so a just-added or offline
        // server shows immediately). The "all" sentinel is never listed as a server.
        const candidates = new Set(lastData.routableServers || []);
        (r.visibleTo || []).forEach(s => { if (s !== '*') candidates.add(s); });
        curSet.forEach(s => { if (s !== '*') candidates.add(s); });
        const rows = Array.from(candidates).sort().map(s => {
            const checked = curSet.has(s) ? 'checked' : '';
            const routable = (lastData.routableServers || []).includes(s);
            const tag = routable ? '' : '<span class="badge badge-out" title="no current route — pending">pending</span>';
            return `
            <label class="exposed-room">
                <input type="checkbox" class="roomvis-cb" data-jid="${escHtml(r.jid)}" value="${escHtml(s)}" ${checked}
                       onchange="captureRoomVisEdits('${jsArg(r.jid)}')">
                <span>${escHtml(s)}</span> ${tag}
            </label>`;
        }).join('') || '<p class="empty" style="margin:4px 0">No servers selected — this room is visible to nobody.</p>';
        body = `<div class="roomvis-list">${rows}</div>
            <div style="margin-top:8px;display:flex;gap:6px;align-items:center;flex-wrap:wrap">
                <input type="text" id="roomvis-add-${id}" placeholder="add a server not listed yet"
                       style="padding:4px 6px;font-size:12px;min-width:220px"
                       onkeydown="if(event.key==='Enter'){addRoomVisServer('${jsArg(r.jid)}');event.preventDefault();}">
                <button class="btn-small" style="background:#e2e3e5;color:#383d41" onclick="addRoomVisServer('${jsArg(r.jid)}')">Add</button>
            </div>`;
    }

    return `
            <label class="exposed-room" style="font-weight:600">
                <input type="checkbox" ${allOn ? 'checked' : ''} onchange="setRoomVisAll('${jsArg(r.jid)}', this.checked)">
                <span>Visible to all peers</span>
            </label>
            ${body}
            <div style="margin-top:8px">
                <button class="btn-small btn-primary" onclick="saveRoomVis('${jsArg(r.jid)}')">Save</button>
                <span id="roomvis-saved-${id}" style="display:none;color:#28a745;font-size:12px;margin-left:6px">Saved ✓</span>
            </div>`;
}

function roomVisibleToSet(jid) {
    if (editedRoomVis[jid]) return editedRoomVis[jid];
    const room = (lastData.localRooms || []).find(x => x.jid === jid) || {};
    return new Set(room.visibleTo || []);
}

function setRoomVisAll(jid, checked) {
    captureRoomVisEdits(jid);
    const set = roomVisibleToSet(jid);
    if (checked) { set.clear(); set.add('*'); } else { set.delete('*'); }
    editedRoomVis[jid] = set;
    renderLocalRooms(lastData.localRooms || []);
}

function captureRoomVisEdits(jid) {
    const boxes = document.querySelectorAll(`.roomvis-cb[data-jid="${cssEscape(jid)}"]`);
    if (boxes.length === 0 && !editedRoomVis[jid]) return;
    const set = editedRoomVis[jid] || new Set();
    boxes.forEach(b => { if (b.checked) set.add(b.value); else set.delete(b.value); });
    editedRoomVis[jid] = set;
}

function toggleRoomDetail(jid) {
    if (expandedRoomVis.has(jid)) { captureRoomVisEdits(jid); expandedRoomVis.delete(jid); }
    else expandedRoomVis.add(jid);
    renderLocalRooms(lastData.localRooms || []);
}

function addRoomVisServer(jid) {
    const id = jidToElemId(jid);
    const input = document.getElementById('roomvis-add-' + id);
    if (!input) return;
    const srv = input.value.trim().toLowerCase();
    if (!srv) return;
    captureRoomVisEdits(jid);
    const set = roomVisibleToSet(jid);
    set.delete('*');   // adding a specific server means we're no longer in "all" mode
    set.add(srv);
    editedRoomVis[jid] = set;
    input.value = '';
    renderLocalRooms(lastData.localRooms || []);
}

function saveRoomVis(jid) {
    captureRoomVisEdits(jid);
    const set = roomVisibleToSet(jid);
    post({ action: 'set-room-visibility', jid, servers: Array.from(set).join(',') })
        .then(result => {
            if (result && result.ok) {
                delete editedRoomVis[jid];
                flashSaved('Saved ✓');
                refresh();
            }
        });
}

/**
 * Removes a mapping.  If remoteDomain is provided, only that spoke's mapping
 * is removed (targeted unmap).  If omitted, all mappings for the room are removed.
 */
function unmapRoom(localJid, remoteDomain) {
    const target = remoteDomain || 'all peers';
    if (!confirm('Remove mapping between ' + localJid + ' and ' + target + '?')) return;
    const params = { action: 'unmap-room', localJid };
    if (remoteDomain) params.remoteDomain = remoteDomain;
    post(params).then(refresh);
}

// ── Remote rooms (collapsible per peer) ───────────────────────────────────────

function renderRemoteRooms(remoteRooms, localRooms, mappings) {
    const container = document.getElementById('remote-rooms-container');
    const peers = Object.keys(remoteRooms);
    if (peers.length === 0) {
        container.innerHTML = '<p class="empty">No remote rooms discovered yet. Enable a room for federation on a connected server — it will appear here automatically.</p>';
        return;
    }

    // Reverse index: remoteRoomJid → {localJid, remoteDomain}
    const reverseMap = {};
    for (const [localJid, mList] of Object.entries(mappings)) {
        for (const m of mList) {
            reverseMap[m.remoteRoomJid] = { localJid, remoteDomain: m.remoteDomain };
        }
    }

    container.innerHTML = peers.map(peer => {
        const rooms      = remoteRooms[peer];
        const peerId     = jidToElemId(peer);
        const isCollapsed = collapsedPeers.has(peer);

        // Federated local rooms not already mapped to this specific peer.
        const mappedToPeer = new Set(
            Object.entries(mappings)
                .filter(([, mList]) => mList.some(m => m.remoteDomain === peer))
                .map(([localJid]) => localJid)
        );
        const available = localRooms.filter(r => r.federated && !mappedToPeer.has(r.jid));

        const rows = rooms.length === 0
            ? '<tr><td colspan="3" class="empty">No federated rooms on this server.</td></tr>'
            : rooms.map(r => {
                const mapped = reverseMap[r.jid];
                let mapCell;
                if (mapped) {
                    mapCell = `<span class="badge badge-fed">↔ ${escHtml(mapped.localJid)}</span>
                               <button class="btn-small btn-danger" style="margin-left:4px"
                                       onclick="unmapRoom('${jsArg(mapped.localJid)}','${jsArg(mapped.remoteDomain)}')">Unmap</button>`;
                } else if (available.length === 0) {
                    mapCell = '<span style="color:#999;font-size:11px">no local rooms available<br>(enable federation on a local room first)</span>';
                } else {
                    const selId = 'mapsel_' + peerId + '_' + jidToElemId(r.jid);
                    const options = available.map(l =>
                        `<option value="${escHtml(l.jid)}">${escHtml(l.jid)}</option>`
                    ).join('');
                    mapCell = `<select id="${selId}" style="font-size:12px;padding:2px 4px;max-width:200px">
                                   <option value="">— choose local room —</option>
                                   ${options}
                               </select>
                               <button class="btn-small btn-primary" style="margin-left:4px"
                                       onclick="mapRoom('${jsArg(r.jid)}','${jsArg(peer)}','${selId}')">Map</button>`;
                }
                return `
                <tr>
                    <td><strong>${escHtml(r.name || r.jid)}</strong><br><small>${escHtml(r.jid)}</small></td>
                    <td>${escHtml(r.description || '—')}</td>
                    <td style="min-width:220px">${mapCell}</td>
                </tr>`;
            }).join('');

        // Count active mappings for this peer (shown in header when collapsed).
        const activeMappings = Object.values(mappings)
            .flat()
            .filter(m => m.remoteDomain === peer).length;
        const mapInfo = activeMappings > 0
            ? `<span class="peer-map-count">${activeMappings} mapped</span>`
            : '';

        return `
        <div class="peer-section">
            <div class="peer-section-header" onclick="togglePeer('${jsArg(peer)}')">
                <span class="peer-collapse-icon" id="peer-icon-${peerId}">${isCollapsed ? '▶' : '▼'}</span>
                <strong>${escHtml(peer)}</strong>
                <span class="peer-room-count">${rooms.length} room(s)</span>
                ${mapInfo}
            </div>
            <div class="peer-section-body" id="peer-body-${peerId}"
                 style="${isCollapsed ? 'display:none' : ''}">
                <table class="fed-table">
                    <thead><tr><th>Remote room</th><th>Description</th><th>Local mapping</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        </div>`;
    }).join('');
}

function togglePeer(peer) {
    const wasCollapsed = collapsedPeers.has(peer);
    if (wasCollapsed) collapsedPeers.delete(peer); else collapsedPeers.add(peer);

    const peerId = jidToElemId(peer);
    const body   = document.getElementById('peer-body-' + peerId);
    const icon   = document.getElementById('peer-icon-' + peerId);
    if (body) body.style.display = wasCollapsed ? '' : 'none';
    if (icon) icon.textContent   = wasCollapsed ? '▼' : '▶';
}

function mapRoom(remoteJid, remoteDomain, selId) {
    const sel = document.getElementById(selId);
    if (!sel || !sel.value) {
        alert('Please select a local room to map to.');
        return;
    }
    post({ action: 'map-room', localJid: sel.value, remoteJid, remoteDomain })
        .then(d => { if (d && d.error) alert(d.error); else flashSaved('Mapping requested ✓'); refresh(); });
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function jidToElemId(jid) {
    return jid.replace(/[^a-zA-Z0-9]/g, '_');
}

// Uniform "change applied" confirmation. A single green toast so it survives the table
// re-renders that would otherwise wipe an inline badge, and works for every save/apply button.
let _toastTimer = null;
function flashSaved(label) {
    const t = document.getElementById('fed-toast');
    if (!t) return;
    t.textContent = '✓ ' + (label || 'Saved');
    t.classList.add('show');
    clearTimeout(_toastTimer);
    _toastTimer = setTimeout(() => t.classList.remove('show'), 2000);
}

function post(params) {
    // CSRF double-submit: echo the fed-csrf cookie the server handed us on GET.
    const csrf = getCookie('fed-csrf');
    const body = new URLSearchParams(csrf ? Object.assign({}, params, { csrf }) : params);
    return fetch(API_URL, { method: 'POST', body, credentials: 'same-origin' })
        .then(r => r.json())
        .catch(err => console.error('POST error:', err));
}

function getCookie(name) {
    const m = document.cookie.match('(?:^|; )' +
        name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '=([^;]*)');
    return m ? decodeURIComponent(m[1]) : '';
}

function escHtml(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// Escapes a value for safe interpolation into a SINGLE-QUOTED JS string that itself sits inside a
// double-quoted inline handler attribute — e.g. onclick="fn('${jsArg(x)}')". HTML-entity escaping
// alone (escHtml) is NOT enough here: the browser HTML-decodes the attribute value BEFORE the JS
// parser runs, so an entity-encoded quote would decode back to a real quote and break out of the
// string. So we backslash-escape the JS metacharacters first, then HTML-escape the attribute-
// breaking characters. Output is also safe in a double-quoted HTML attribute or HTML text context.
function jsArg(s) {
    if (s == null) return '';
    return String(s)
        .replace(/\\/g, '\\\\')
        .replace(/'/g, "\\'")
        .replace(/\r/g, '\\r')
        .replace(/\n/g, '\\n')
        .replace(/</g, '\\x3C')
        .replace(/&/g, '&amp;')
        .replace(/"/g, '&quot;');
}
