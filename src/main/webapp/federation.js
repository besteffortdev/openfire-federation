/* federation.js — admin console client-side logic */

const API_URL = 'api';
const POLL_MS = 5000;

let pollTimer  = null;
let roomFilter = '';

// Tracks which peer sections are manually collapsed across refreshes.
const collapsedPeers = new Set();

// Latest status payload, so per-peer server editors can be built from the peer's exposableServers.
let lastData = {};
// Untrusted peers whose exposed-server editor is expanded (persisted across refreshes).
const expandedPeerRooms = new Set();
// In-flight exposed-server edits per domain (server -> checked) so a 5 s refresh doesn't clobber them.
const editedExposed = {};
// Federated rooms whose per-room visibility editor is expanded (persisted across refreshes).
const expandedRoomVis = new Set();
// In-flight visibility edits per room jid (server -> checked) so a 5 s refresh doesn't clobber them.
const editedRoomVis = {};

// ── Bootstrap ────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    setupTabs();
    bindPeerForm();
    bindRoomSearch();
    refresh();
    pollTimer = setInterval(refresh, POLL_MS);
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
}

// ── Data refresh ──────────────────────────────────────────────────────────────

function refresh() {
    fetch(API_URL + '?action=status')
        .then(r => r.json())
        .then(data => {
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
            renderPendingRequests(data.pendingRequests || []);
            renderLocalRooms(localRooms);
            renderRemoteRooms(data.remoteRooms || {}, localRooms, mappings);
            updateStatusBadge(data.peers || []);
            updateKeepaliveInput(data.keepaliveSeconds);
            updateReconnectInput(data.reconnectSeconds);
            updateAllowlistToggle(data.peerAllowlist);
        })
        .catch(err => console.error('Federation API error:', err));
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

    const now = Date.now();
    tbody.innerHTML = peers.map(p => {
        const isWithdrawn      = p.status === 'WITHDRAWN';
        const isUnreachable    = p.status === 'UNREACHABLE';
        const isDisabled       = p.status === 'DISABLED';
        const isRemoteDisabled = p.status === 'REMOTE_DISABLED';
        const needsRetry = isWithdrawn || isUnreachable || p.status === 'UNKNOWN';

        let actionBtns = '';
        if (isDisabled) {
            // We disabled this peer — only we can re-enable it.
            actionBtns = `<button class="btn-small btn-primary" style="margin-right:4px"
                       onclick="enablePeer('${escHtml(p.domain)}')">Enable</button>`;
        } else if (isRemoteDisabled) {
            // Disabled by the remote — cannot be re-enabled from this side.
            actionBtns = '';
        } else {
            if (needsRetry) {
                actionBtns += `<button class="btn-small btn-warn" style="margin-right:4px"
                       onclick="retryPeer('${escHtml(p.domain)}')">${isWithdrawn ? 'Reconnect' : 'Retry'}</button>`;
            }
            actionBtns += `<button class="btn-small btn-warn" style="margin-right:4px"
                       onclick="disablePeer('${escHtml(p.domain)}')">Disable</button>`;
        }

        // Trust controls: toggle untrusted, and (when untrusted) open the exposed-server editor.
        const trustBtn = p.untrusted
            ? `<button class="btn-small btn-primary" style="margin-right:4px"
                       onclick="setUntrusted('${escHtml(p.domain)}', false)">Make trusted</button>`
            : `<button class="btn-small btn-warn" style="margin-right:4px"
                       onclick="setUntrusted('${escHtml(p.domain)}', true)">Make untrusted</button>`;
        const expanded = expandedPeerRooms.has(p.domain);
        const roomsBtn = p.untrusted
            ? `<button class="btn-small" style="margin-right:4px;background:#e2e3e5;color:#383d41"
                       onclick="togglePeerRooms('${escHtml(p.domain)}')">Servers ${expanded ? '▾' : '▸'}</button>`
            : '';

        let statusLabel;
        if (isWithdrawn)               statusLabel = 'Disconnected by remote';
        else if (isDisabled)           statusLabel = 'Disabled';
        else if (isRemoteDisabled)     statusLabel = 'Disabled by remote';
        else if (p.status === 'TRUST_MISMATCH')
            statusLabel = 'Trust mismatch — both ends must set the same trust level';
        else                           statusLabel = p.status;
        if (isUnreachable) {
            const retryAt = p.nextRetryAt || 0;
            if (retryAt > now) {
                const secsLeft = Math.ceil((retryAt - now) / 1000);
                statusLabel += ` <span class="retry-countdown">next retry in ${secsLeft}s</span>`;
            } else {
                statusLabel += ` <span class="retry-countdown">retrying…</span>`;
            }
        }

        const untrustedBadge = p.untrusted
            ? `<span class="badge badge-untrusted" title="Shares only the servers you expose">untrusted · ${(p.exposedServers || []).length} server(s)</span>`
            : '';

        // S2S cert pinning: a changed cert (possible impersonation) shows a red alert + accept
        // button; a quietly-pinned cert shows an unobtrusive lock.
        const certBadge = p.certMismatch
            ? `<span class="badge badge-untrusted" title="The S2S certificate changed since it was first pinned">⚠ cert changed</span>`
            : (p.certPinned ? `<span class="badge badge-in" title="S2S certificate pinned (trust-on-first-use)">🔒 pinned</span>` : '');
        const certBtn = p.certMismatch
            ? `<button class="btn-small btn-warn" style="margin-right:4px"
                       onclick="acceptCert('${escHtml(p.domain)}')">Trust new cert</button>`
            : '';

        let row = `
        <tr>
            <td>${escHtml(p.domain)}${untrustedBadge}${certBadge}</td>
            <td><span class="status-dot ${statusClass(p.status)}"></span> ${statusLabel}</td>
            <td>${p.lastSeen ? new Date(p.lastSeen).toLocaleString() : '—'}</td>
            <td style="white-space:nowrap">
                ${certBtn}${actionBtns}${trustBtn}${roomsBtn}
                <button class="btn-small btn-danger"
                        onclick="removePeer('${escHtml(p.domain)}')">Remove</button>
            </td>
        </tr>`;

        if (p.certMismatch) {
            row += `
        <tr class="exposed-editor-row">
            <td colspan="4" style="color:#58151c">
                ⚠ <strong>${escHtml(p.domain)}</strong> presented a different S2S certificate than the
                one pinned on first contact — this can mean a server was re-created, or an impersonation
                attempt. The peer has been auto-marked untrusted. If you trust the change, click
                <em>Trust new cert</em>; otherwise investigate before restoring trust.
            </td>
        </tr>`;
        }

        if (p.untrusted && expanded) {
            row += renderExposedServerEditor(p);
        }
        return row;
    }).join('');
}

// ── Untrusted peers: exposed-server editor ─────────────────────────────────────

function renderExposedServerEditor(p) {
    const id = jidToElemId(p.domain);
    // ── Left: SERVERS we expose to this peer (editable). ──
    // Pending edits win over the persisted set so a refresh doesn't reset the admin's work.
    const checkedSet = editedExposed[p.domain] || new Set(p.exposedServers || []);
    const candidates = (p.exposableServers || []).slice();
    // Include any exposed server no longer in the candidate list (e.g. route lost) so it's not
    // silently dropped from the UI while still persisted.
    (p.exposedServers || []).forEach(s => { if (!candidates.includes(s)) candidates.push(s); });

    const leftList = candidates.length === 0
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
                <input type="checkbox" class="exposed-cb" data-domain="${escHtml(p.domain)}"
                       value="${escHtml(s)}" ${checked}
                       onchange="captureExposedEdits('${escHtml(p.domain)}')">
                <span>${escHtml(s)}</span> ${tag}
            </label>`;
        }).join('');

    // ── Right: SERVERS this peer advertises through to us (read-only). ──
    const adv = p.advertisedVia || [];
    const rightList = adv.length === 0
        ? '<p class="empty" style="margin:4px 0">This peer is not advertising any servers to us yet.</p>'
        : adv.map(s => `
            <div class="exposed-room"><span>${escHtml(s)}</span></div>`).join('');

    return `
        <tr class="exposed-editor-row">
            <td colspan="4">
                <div class="exposed-cols">
                    <div class="exposed-col">
                        <div class="exposed-col-h">↑ Servers exposed to ${escHtml(p.domain)}
                            <span class="exposed-col-sub">this peer sees each checked server's federated rooms and a route to it</span>
                        </div>
                        ${leftList}
                        <div style="margin-top:8px">
                            <button class="btn-small btn-primary" onclick="saveExposedServers('${escHtml(p.domain)}')">Save</button>
                            <span id="exposed-saved-${id}" style="display:none;color:#28a745;font-size:12px;margin-left:8px">Saved ✓</span>
                        </div>
                    </div>
                    <div class="exposed-col">
                        <div class="exposed-col-h">↓ Servers ${escHtml(p.domain)} advertises through
                            <span class="exposed-col-sub">what the remote is exposing inbound (read-only)</span>
                        </div>
                        ${rightList}
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
        + 'Use "Servers" to choose exactly which servers it may see.')) return;
    post({ action: 'set-untrusted', domain, untrusted }).then(() => {
        if (!untrusted) { expandedPeerRooms.delete(domain); delete editedExposed[domain]; }
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
                const badge = document.getElementById('exposed-saved-' + jidToElemId(domain));
                if (badge) {
                    badge.style.display = 'inline';
                    setTimeout(() => { badge.style.display = 'none'; }, 2500);
                }
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
                     onclick="killSession('${escHtml(domain)}','${escHtml(s.direction)}')">
                 Kill ${s.direction === 'outgoing' ? '↑' : '↓'}
             </button>`
        ).join('');

        return `
        <tr>
            <td><strong>${escHtml(domain)}</strong>${fedBadge}</td>
            <td>${dirBadges}</td>
            <td class="ts">${new Date(earliest).toLocaleString()}</td>
            <td>${tlsBadge}</td>
            <td style="white-space:nowrap">${killBtns}</td>
        </tr>`;
    }).join('');
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
                const badge = document.getElementById('keepalive-saved');
                if (badge) {
                    badge.style.display = 'inline';
                    setTimeout(() => { badge.style.display = 'none'; }, 2500);
                }
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
                const badge = document.getElementById('reconnect-saved');
                if (badge) {
                    badge.style.display = 'inline';
                    setTimeout(() => { badge.style.display = 'none'; }, 2500);
                }
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
                const badge = document.getElementById('allowlist-saved');
                if (badge) {
                    badge.style.display = 'inline';
                    setTimeout(() => { badge.style.display = 'none'; }, 2500);
                }
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
    if (s === 'WITHDRAWN')   return 'orange';
    if (s === 'TRUST_MISMATCH') return 'orange';
    if (s === 'DISABLED' || s === 'REMOTE_DISABLED') return 'red';
    return 'grey';
}

// ── Routing table tab ─────────────────────────────────────────────────────────

function renderRouting(entries) {
    const tbody = document.getElementById('routing-tbody');
    if (entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="empty">No routes yet — waiting for S2S connections.</td></tr>';
        return;
    }
    entries.sort((a, b) => a.hops - b.hops);
    tbody.innerHTML = entries.map(r => `
        <tr>
            <td>${escHtml(r.destination)}</td>
            <td>${escHtml(r.nextHop)}</td>
            <td>${r.hops}</td>
            <td class="ts">${new Date(r.updatedAt).toLocaleTimeString()}</td>
        </tr>
    `).join('');
}

// ── Room search ───────────────────────────────────────────────────────────────

function bindRoomSearch() {
    document.getElementById('room-search').addEventListener('input', e => {
        roomFilter = e.target.value.toLowerCase();
        document.querySelectorAll('#local-rooms-tbody tr[data-jid]').forEach(row => {
            row.style.display = row.dataset.jid.toLowerCase().includes(roomFilter) ? '' : 'none';
        });
    });
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
        let mappedCell;
        if (r.mappings && r.mappings.length > 0) {
            mappedCell = r.mappings.map(m => renderMappingRow(r.jid, m)).join('');
        } else {
            mappedCell = '<span style="color:#999;font-size:11px">not mapped</span>';
        }
        // Per-room visibility control (only meaningful while the room is federated).
        const visList = r.visibleTo || [];
        const visExpanded = expandedRoomVis.has(r.jid);
        const visLabel = visList.includes('*') ? 'all'
                       : (visList.length ? visList.length + ' server(s)' : 'none');
        const visCtl = r.federated
            ? `<div style="margin-top:6px">
                   <button class="btn-small" style="background:#e2e3e5;color:#383d41"
                           onclick="toggleRoomVis('${escHtml(r.jid)}')">Visible: ${visLabel} ${visExpanded ? '▾' : '▸'}</button>
               </div>`
            : '';
        const visible = r.jid.toLowerCase().includes(roomFilter) ? '' : 'display:none';
        let row = `
        <tr data-jid="${escHtml(r.jid)}" style="${visible}">
            <td><strong>${escHtml(r.name || r.jid)}</strong><br><small>${escHtml(r.jid)}</small></td>
            <td>${escHtml(r.description || '—')}</td>
            <td>${r.occupants}</td>
            <td>
                <label class="toggle">
                    <input type="checkbox" ${r.federated ? 'checked' : ''}
                           onchange="setRoomFederated('${escHtml(r.jid)}', this.checked)">
                    <span class="slider"></span>
                </label>
                ${visCtl}
                ${r.federated ? `<label style="display:block;margin-top:6px;font-size:11px;color:#495057">
                    <input type="checkbox" ${r.autoAccept ? 'checked' : ''}
                           onchange="setRoomAutoAccept('${escHtml(r.jid)}', this.checked)">
                    auto-accept requests</label>` : ''}
            </td>
            <td>${mappedCell}</td>
        </tr>`;
        if (r.federated && visExpanded) row += renderRoomVisEditor(r);
        return row;
    }).join('');
}

function setRoomFederated(jid, federated) {
    post({ action: 'set-room', jid, federated: federated.toString() }).then(refresh);
}

function setRoomAutoAccept(jid, autoAccept) {
    post({ action: 'set-room-autoaccept', jid, autoAccept: autoAccept.toString() }).then(refresh);
}

// ── Mapping consent: inline rows + pending-requests panel ───────────────────────

/** Renders one mapping row with a state badge and the buttons valid for that state. */
function renderMappingRow(localJid, m) {
    const dom = escHtml(m.remoteDomain);
    const lj = escHtml(localJid);
    const target = `<span class="badge badge-fed" title="${dom}">${escHtml(m.remoteRoomJid)}</span>`;
    const unmap = `<button class="btn-small btn-danger" onclick="unmapRoom('${lj}','${dom}')">Unmap</button>`;
    let badge, buttons;
    switch (m.state) {
        case 'ACTIVE':
            badge = (m.connected === false)
                ? `<span class="mapping-state mapping-disconnected" title="Route to ${dom} is down">⚠ disconnected</span>`
                : `<span class="mapping-state mapping-connected">● active</span>`;
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
        post({ action: 'map-room', localJid, remoteJid: m.remoteRoomJid, remoteDomain }).then(refresh);
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
            <button class="btn-small btn-primary" onclick="mappingAction('accept-mapping','${escHtml(req.localJid)}','${escHtml(req.remoteDomain)}')">Accept</button>
            <button class="btn-small btn-warn" onclick="mappingAction('reject-mapping','${escHtml(req.localJid)}','${escHtml(req.remoteDomain)}')">Reject</button>
        </div>`).join('');
}

// ── Per-room visibility ACL editor ─────────────────────────────────────────────

function renderRoomVisEditor(r) {
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
                       onchange="captureRoomVisEdits('${escHtml(r.jid)}')">
                <span>${escHtml(s)}</span> ${tag}
            </label>`;
        }).join('') || '<p class="empty" style="margin:4px 0">No servers selected — this room is visible to nobody.</p>';
        body = `${rows}
            <div style="margin-top:8px;display:flex;gap:6px;align-items:center;flex-wrap:wrap">
                <input type="text" id="roomvis-add-${id}" placeholder="add a server not listed yet"
                       style="padding:4px 6px;font-size:12px;min-width:220px"
                       onkeydown="if(event.key==='Enter'){addRoomVisServer('${escHtml(r.jid)}');event.preventDefault();}">
                <button class="btn-small" style="background:#e2e3e5;color:#383d41" onclick="addRoomVisServer('${escHtml(r.jid)}')">Add</button>
            </div>`;
    }

    return `
    <tr class="exposed-editor-row">
        <td colspan="5">
            <div class="exposed-col-h">Servers allowed to see <strong>${escHtml(r.name || r.jid)}</strong>
                <span class="exposed-col-sub">none selected = visible to nobody; tick “all peers” to share with everyone; a checked server with no route yet is pending</span>
            </div>
            <label class="exposed-room" style="font-weight:600">
                <input type="checkbox" ${allOn ? 'checked' : ''} onchange="setRoomVisAll('${escHtml(r.jid)}', this.checked)">
                <span>Visible to all peers</span>
            </label>
            ${body}
            <div style="margin-top:8px">
                <button class="btn-small btn-primary" onclick="saveRoomVis('${escHtml(r.jid)}')">Save</button>
                <span id="roomvis-saved-${id}" style="display:none;color:#28a745;font-size:12px;margin-left:6px">Saved ✓</span>
            </div>
        </td>
    </tr>`;
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

function toggleRoomVis(jid) {
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
                const badge = document.getElementById('roomvis-saved-' + jidToElemId(jid));
                if (badge) { badge.style.display = 'inline'; setTimeout(() => { badge.style.display = 'none'; }, 2500); }
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
                                       onclick="unmapRoom('${escHtml(mapped.localJid)}','${escHtml(mapped.remoteDomain)}')">Unmap</button>`;
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
                                       onclick="mapRoom('${escHtml(r.jid)}','${escHtml(peer)}','${selId}')">Map</button>`;
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
            <div class="peer-section-header" onclick="togglePeer('${escHtml(peer)}')">
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
    post({ action: 'map-room', localJid: sel.value, remoteJid, remoteDomain }).then(refresh);
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function jidToElemId(jid) {
    return jid.replace(/[^a-zA-Z0-9]/g, '_');
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
