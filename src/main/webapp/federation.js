/* Client-side logic of the federation admin console: renders the status document and posts admin actions. */

const API_URL = 'api';

// The latest status document.
let lastData = {};
// Peers whose settings panel is open, kept across re-renders.
const expandedPeerRooms = new Set();
// Unsaved exposed-server edits per peer, so a refresh does not discard them.
const editedExposed = {};
// Rooms whose settings panel is open, kept across re-renders.
const expandedRoomVis = new Set();
// Unsaved visibility edits per room, so a refresh does not discard them.
const editedRoomVis = {};

document.addEventListener('DOMContentLoaded', () => {
    setupTabs();
    bindPeerForm();
    bindRoomDefaultForm();
    bindFilters();
    bindTableSorting();
    watchRenders();
    pollLoop();
    setInterval(tickClocks, 1000);
});

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

// Fetches the status once, for immediate feedback after an action; pollLoop() delivers every other update.
function refresh() {
    fetch(API_URL + '?action=status')
        .then(r => r.json())
        .then(renderAll)
        .catch(err => console.error('Federation API error:', err));
}

// Long-polls the status: the server answers when it changes, or after about 25 seconds.
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
    renderUsersTab(data.contactLists || {});
    renderPendingRequests(data.pendingRequests || []);
    renderLocalRooms(localRooms);
    renderRoomDefaults(data.roomDefaults || []);
    renderRemoteRooms(data.remoteRooms || {}, localRooms, mappings);
    updateStatusBadge(data.peers || []);
    updateSettings(data);
    renderAvScanLog(data.avScanLog || []);
    renderRejectedFiles(data.rejectedFiles || []);
    renderFileConfig(data.fileConfig || {});
}

function updateStatusBadge(peers) {
    const badge = document.getElementById('fed-status-badge');
    const reachable = peers.filter(p => p.status === 'REACHABLE').length;
    badge.textContent = reachable + '/' + peers.length + ' peers reachable';
    badge.className = 'fed-badge ' + (reachable > 0 ? 'ok' : peers.length === 0 ? 'neutral' : 'warn');
}

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
        save({ action: 'add-peer', domain, untrusted }, 'Peer added').then(result => {
            if (!result || !result.ok) return;
            domainField.value = '';
            if (untrustedCb) untrustedCb.checked = false;
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
    // Captured before the rebuild, so a refresh mid-edit keeps the admin's unsaved changes.
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

        // A changed certificate gets an alert with an accept button; a pinned one a quiet lock.
        const certBadge = p.certMismatch
            ? `<span class="badge badge-untrusted" title="The S2S certificate changed since it was first pinned">⚠ cert changed</span>`
            : (p.certPinned ? `<span class="badge badge-in" title="S2S certificate pinned (trust-on-first-use)">🔒 pinned</span>` : '');

        // Summary next to the expand arrow.
        const sumParts = [];
        sumParts.push(p.untrusted ? `${(p.exposedServers || []).length} exposed` : 'trusted');
        if (deniedCount) sumParts.push(`${deniedCount} denied`);
        const dom = jsArg(p.domain);

        let row = `
        <tr>
            <td data-sort="${escHtml(p.domain)}">${dom}${untrustedBadge}${certBadge}</td>
            <td data-sort="${escHtml(p.status)}"><span class="status-dot ${statusClass(p.status)}"></span> ${statusLabel}</td>
            <td data-sort="${p.lastSeen || ''}">${p.lastSeen ? new Date(p.lastSeen).toLocaleString() : '—'}</td>
            <td class="room-detail-cell">
                <span class="room-detail-sum">${sumParts.join(' · ')}</span>
                <button class="room-expand-btn ${expanded ? 'open' : ''}"
                        title="${expanded ? 'Hide' : 'Show'} settings for this peer"
                        onclick="togglePeerRooms('${dom}')">▸</button>
            </td>
        </tr>`;

        if (p.certMismatch) {
            row += `
        <tr class="detail-row exposed-editor-row">
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

/** Full-width detail row behind the expand arrow: every setting for one peer in one view. */
function renderPeerDetailRow(p) {
    const dom = jsArg(p.domain);
    const id = jidToElemId(p.domain);
    const isWithdrawn = p.status === 'WITHDRAWN';
    const needsRetry = isWithdrawn || p.status === 'UNREACHABLE'
                    || p.status === 'PENDING' || p.status === 'UNKNOWN';

    let actionBtns = '';
    if (p.status === 'DISABLED') {
        actionBtns = `<button class="btn-small btn-primary" onclick="enablePeer('${dom}')">Enable</button>`;
    } else if (p.status === 'REMOTE_DISABLED') {
        actionBtns = '<span class="exposed-col-sub">Disabled by the remote — only they can re-enable it.</span>';
    } else {
        if (needsRetry) {
            actionBtns += `<button class="btn-small btn-warn" onclick="retryPeer('${dom}')">${isWithdrawn ? 'Reconnect' : 'Retry'}</button>`;
        }
        actionBtns += `<button class="btn-small btn-warn" onclick="disablePeer('${dom}')">Disable</button>`;
    }

    // Outbound: trust, and the servers exposed to an untrusted peer; unsaved edits win over the stored set.
    const trustBtn = p.untrusted
        ? `<button class="btn-small btn-primary" onclick="setUntrusted('${dom}', false)">Make trusted</button>`
        : `<button class="btn-small btn-warn" onclick="setUntrusted('${dom}', true)">Make untrusted</button>`;
    let outbound;
    if (p.untrusted) {
        const checkedSet = editedExposed[p.domain] || new Set(p.exposedServers || []);
        const candidates = (p.exposableServers || []).slice();
        // A stored server that is no longer a candidate (its route was lost) stays listed.
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
            </div>`;
    } else {
        outbound = '<p class="empty" style="margin:4px 0">Trusted — this peer sees the full topology and all federated rooms.</p>';
    }

    // Whether contact lists may be sent over this untrusted link; trusted links always carry them.
    const contactLists = p.untrusted ? `
                        <label class="exposed-room" style="margin-top:10px;border-top:1px solid var(--line);padding-top:8px;align-items:flex-start">
                            <input type="checkbox" ${p.contactListsAllowed ? 'checked' : ''}
                                   onchange="setPeerContactLists('${dom}', this.checked)">
                            <span><strong>Send shared contact lists to ${dom}</strong><br>
                                <small>Lists from this server and the exposed servers can cross this link. Nothing is needed on ${dom}'s side to receive them.</small></span>
                        </label>
                        <label class="exposed-room" style="margin-top:6px;align-items:flex-start">
                            <input type="checkbox" ${p.contactListsRefused ? 'checked' : ''}
                                   onchange="setPeerContactListsRefused('${dom}', this.checked)">
                            <span><strong>Refuse contact lists from ${dom}</strong><br>
                                <small>Drops every list arriving over this link, including lists for servers behind this one.</small></span>
                        </label>` : '';

    // Inbound: what this peer advertises to us, each of which can be denied.
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
        <tr class="detail-row exposed-editor-row room-detail-row">
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
                        ${contactLists}
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
    const set = captureChecks(`.exposed-cb[data-domain="${cssEscape(domain)}"]`);
    if (set) editedExposed[domain] = set;
}

function togglePeerRooms(domain) {
    toggleExpanded(expandedPeerRooms, domain, captureExposedEdits);
    renderPeers(lastData.peers || []);
}

function setUntrusted(domain, untrusted) {
    if (untrusted && !confirm('Mark ' + domain + ' as untrusted?\n\n'
        + 'It will immediately stop receiving routing updates and room advertisements. '
        + 'Use the peer\'s settings panel (expand arrow) to choose exactly which servers it may see.')) return;
    save({ action: 'set-untrusted', domain, untrusted }, untrusted ? 'Marked untrusted' : 'Marked trusted')
        .then(result => {
            if (result && result.ok && !untrusted) delete editedExposed[domain];   // exposure list no longer applies
        });
}

function acceptCert(domain) {
    if (!confirm('Trust the new S2S certificate for ' + domain + '?\n\n'
        + 'Only do this if you know the server was legitimately re-created or its certificate '
        + 'was renewed. The new certificate will be pinned and the alert cleared.')) return;
    save({ action: 'accept-cert', domain }, 'Certificate trusted');
}

function setPeerContactLists(domain, enabled) {
    save({ action: 'set-peer-contact-lists', domain, enabled },
         enabled ? 'Contact lists allowed' : 'Contact lists blocked');
}

function setPeerContactListsRefused(domain, refused) {
    save({ action: 'set-peer-contact-lists-refused', domain, refused },
         'Contact lists from ' + domain + (refused ? ' refused' : ' accepted'));
}

function saveExposedServers(domain) {
    captureExposedEdits(domain);
    const set = editedExposed[domain] || new Set();
    save({ action: 'set-exposed-servers', domain, servers: Array.from(set).join(',') }).then(result => {
        if (result && result.ok) delete editedExposed[domain];   // persisted set is now authoritative
    });
}

/** Minimal CSS.escape fallback for attribute selectors (domains are simple, but be safe). */
function cssEscape(s) {
    return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
}

function retryPeer(domain) {
    save({ action: 'retry-peer', domain }, 'Retry requested');
}

function disablePeer(domain) {
    if (!confirm('Disable federation with ' + domain + '?\n\n'
        + 'This tears down the connection like removing it, but the remote is told '
        + 'and cannot re-enable it from their side — even by deleting and re-adding it. '
        + 'Only you can re-enable it here.')) return;
    save({ action: 'disable-peer', domain }, 'Peer disabled');
}

function enablePeer(domain) {
    save({ action: 'enable-peer', domain }, 'Peer enabled');
}

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
            <td data-sort="${escHtml(domain)}"><strong>${escHtml(domain)}</strong>${fedBadge}</td>
            <td>${dirBadges}</td>
            <td class="ts" data-sort="${earliest}">${new Date(earliest).toLocaleString()}</td>
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
    save({ action: 'add-peer', domain, untrusted }, 'Peer added');
}

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
               + (fc.mappingsRequested || 0) + ' mapping(s) requested, '
               + (fc.settingsUpdated || 0) + ' setting(s) updated.')
            : 'No <federation> block found in openfire.xml — nothing to ingest.';
    }

    const warnEl = document.getElementById('fedconfig-warnings');
    if (warnEl) {
        const warnings = fc.warnings || [];
        warnEl.innerHTML = warnings.map(w => '<div class="fedconfig-warn">⚠ ' + escHtml(w) + '</div>').join('');
    }
}

function reloadFedConfig() {
    save({ action: 'reload-fed-config' },
         result => 'Reloaded: +' + result.peersAdded + ' peer(s), ' + result.roomsUpdated
                   + ' room(s), ' + result.mappingsRequested + ' mapping(s)');
}

// Ties each settings control (by element id) to a status field (key, def) and the action that saves
// it (action, param); check validates, states labels a toggle and confirmOn asks before turning one on.
const SETTINGS = {
    'keepalive-input': {
        key: 'keepaliveSeconds', def: 240, action: 'set-keepalive', param: 'seconds',
        check: v => v >= 30 || 'Keepalive interval must be at least 30 seconds.' },
    'reconnect-input': {
        key: 'reconnectSeconds', def: 30, action: 'set-reconnect', param: 'seconds',
        check: v => v >= 5 || 'Reconnect interval must be at least 5 seconds.' },
    'mapping-ping-input': {
        key: 'mappingPingSeconds', def: 30, action: 'set-mapping-ping', param: 'seconds',
        check: v => v === 0 || v >= 15 || 'Mapping probe interval must be 0 (probe off) or at least 15 seconds.' },
    'allowlist-toggle': {
        key: 'peerAllowlist', action: 'set-allowlist', states: ['Open federation', 'Approved peers only'],
        confirmOn: 'Restrict federation to approved peers?\n\n'
                 + 'Only peers added on this page will be able to federate; any other server is rejected. '
                 + 'Your existing peers stay approved.' },
    'traversal-toggle': {
        key: 'allowRemoteRoomTraversal', action: 'set-allow-traversal', states: ['Mapping required', 'Remote rooms reachable'] },
    'directrelay-toggle': {
        key: 'directMsgRelay', action: 'set-direct-relay', states: ['Native S2S', 'Relayed over federation'] },
    'probesub-toggle': {
        key: 'probeOnSubscribe', action: 'set-probe-on-subscribe', states: ['Native S2S', 'Probed over federation'] },
    'files-toggle': {
        key: 'filesEnabled', action: 'set-files-enabled', states: ['Off', 'Federated'] },
    'files-maxsize-input': {
        key: 'filesMaxSizeMB', def: 25, action: 'set-files-max-size', param: 'mb',
        check: v => v >= 1 || 'Maximum file size must be at least 1 MB.' },
    'files-retention-input': {
        key: 'filesRetentionDays', def: 90, action: 'set-files-retention', param: 'days',
        check: v => v >= 1 || 'File retention must be at least 1 day.' },
    'files-log-retention-input': {
        key: 'filesLogRetentionDays', def: 180, action: 'set-files-log-retention', param: 'days',
        check: v => v >= 1 || 'Activity log retention must be at least 1 day.' },
    'files-storagedir-input': {
        key: 'filesStorageDir', def: '/var/lib/openfire/federation-files', action: 'set-files-storage-dir', param: 'dir',
        check: v => !v ? 'Storage directory cannot be empty.'
                  : /^(\/|[A-Za-z]:[\\/])/.test(v) || 'Please enter a full path, e.g. /var/lib/openfire/federation-files.' },
    'files-extensions-input': {
        key: 'filesAllowedExtensions', def: '', action: 'set-files-allowed-extensions', param: 'extensions' },
    'files-av-toggle': {
        key: 'filesAvEnabled', action: 'set-files-av-enabled', states: ['Off', 'Scanning'] },
    'files-av-host-input': {
        key: 'filesAvHost', def: 'clamav', action: 'set-files-av-host', param: 'host',
        check: v => !!v || 'ClamAV host cannot be empty.' },
    'files-av-port-input': {
        key: 'filesAvPort', def: 3310, action: 'set-files-av-port', param: 'port',
        check: v => (v >= 1 && v <= 65535) || 'ClamAV port must be between 1 and 65535.' },
};

function updateSettings(data) {
    for (const [id, s] of Object.entries(SETTINGS)) {
        const el = document.getElementById(id);
        if (!el) continue;
        const v = data[s.key] != null ? data[s.key] : s.def;
        const editing = document.activeElement === el;
        if (s.states) {
            if (!editing) el.checked = !!v;
            const lbl = document.getElementById(id.replace(/-toggle$/, '-state'));
            if (lbl) lbl.textContent = s.states[v ? 1 : 0];
        } else if (!editing) {
            el.value = v;
        }
    }
    updateFilesLogPaths(data.filesScanLogPath, data.filesRejectionLogPath);
}

/** A setting's current control value, or null (after telling the admin why) when it is invalid. */
function settingValue(id) {
    const s = SETTINGS[id];
    const el = document.getElementById(id);
    if (!el) return null;
    const v = s.states ? el.checked
            : typeof s.def === 'number' ? parseInt(el.value, 10)
            : el.value.trim();
    const verdict = s.check ? s.check(v) : true;
    if (verdict !== true) {
        alert(verdict);
        return null;
    }
    return v;
}

function saveSetting(id) {
    const s = SETTINGS[id];
    const v = settingValue(id);
    if (v === null) return;
    if (s.confirmOn && v && !confirm(s.confirmOn)) {
        document.getElementById(id).checked = false;
        return;
    }
    save({ action: s.action, [s.param || 'enabled']: v });
}

// Shows where the activity logs are on disk.
function updateFilesLogPaths(scanPath, rejectionPath) {
    const scanEl = document.getElementById('scan-log-path');
    if (scanEl && scanPath) scanEl.textContent = scanPath;
    const rejEl = document.getElementById('rejection-log-path');
    if (rejEl && rejectionPath) rejEl.textContent = rejectionPath;
    const summary = document.getElementById('files-log-paths');
    if (summary && scanPath && rejectionPath) {
        summary.textContent = ' Written to ' + scanPath + ' and ' + rejectionPath + '.';
    }
}

/** Host and port are two settings behind one Save button: both are checked before either is sent. */
function saveFilesAvEndpoint() {
    const ids = ['files-av-host-input', 'files-av-port-input'];
    const values = [];
    for (const id of ids) {
        const v = settingValue(id);
        if (v === null) return;
        values.push(v);
    }
    Promise.all(ids.map((id, i) => post({ action: SETTINGS[id].action, [SETTINGS[id].param]: values[i] })))
        .then(results => {
            const failed = results.find(r => !r || !r.ok);
            if (!failed) flashSaved('Saved');
            else if (failed.error) alert(failed.error);
            refresh();
        });
}

function testFilesAvConnection() {
    const result = document.getElementById('files-av-test-result');
    if (result) result.textContent = 'Testing…';
    post({ action: 'test-av-connection' })
        .then(res => {
            if (!result) return;
            result.textContent = (res && res.reachable) ? '✓ reachable' : '✗ unreachable';
        });
}

function killSession(domain, direction) {
    if (!confirm('Close ' + direction + ' S2S session with ' + domain + '?')) return;
    save({ action: 'kill-session', domain, direction }, 'Session closed');
}

function removePeer(domain) {
    if (!confirm('Remove peer ' + domain + '?')) return;
    save({ action: 'remove-peer', domain }, 'Peer removed');
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

function renderRouting(entries) {
    const tbody = document.getElementById('routing-tbody');
    if (entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="empty">No routes yet — waiting for S2S connections.</td></tr>';
        return;
    }
    // Active routes by hop count, then denied ones.
    entries.sort((a, b) => (a.denied ? 1 : 0) - (b.denied ? 1 : 0) || (a.hops || 0) - (b.hops || 0));
    tbody.innerHTML = entries.map(r => {
        if (r.denied) {
            // A deny stays listed, whatever the peer advertises, until it is allowed again.
            return `
        <tr class="route-denied">
            <td><s>${escHtml(r.destination)}</s></td>
            <td>${escHtml(r.nextHop)}</td>
            <td>—</td>
            <td data-sort=""><span class="badge badge-untrusted" title="Advertisements of this destination from ${escHtml(r.nextHop)} are refused — even if it is withdrawn and offered again">denied</span></td>
            <td><button class="btn-small btn-primary"
                        onclick="allowRoute('${jsArg(r.nextHop)}','${jsArg(r.destination)}')">Allow</button></td>
        </tr>`;
        }
        // Only a learned route can be denied; a direct route is the peer itself.
        const denyBtn = r.destination !== r.nextHop
            ? `<button class="btn-small btn-warn"
                       onclick="denyRoute('${jsArg(r.nextHop)}','${jsArg(r.destination)}')">Deny</button>`
            : '';
        return `
        <tr>
            <td>${escHtml(r.destination)}</td>
            <td>${escHtml(r.nextHop)}</td>
            <td>${r.hops}</td>
            <td class="ts" data-sort="${r.updatedAt}">${new Date(r.updatedAt).toLocaleTimeString()}</td>
            <td>${denyBtn}</td>
        </tr>`;
    }).join('');
}

/** Human-readable byte size (matches the precision used elsewhere on the page: 1 decimal above 1 KB). */
function fmtBytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
}

function renderAvScanLog(entries) {
    const tbody = document.getElementById('av-scan-tbody');
    if (!tbody) return;
    if (entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty">No files scanned yet.</td></tr>';
        return;
    }
    // Server already returns newest-first; keep that order.
    tbody.innerHTML = entries.map(e => {
        const verdict = String(e.verdict || '').toUpperCase();
        const badgeClass = verdict === 'CLEAN' ? 'badge-scan-clean'
                          : verdict === 'INFECTED' ? 'badge-scan-infected'
                          : 'badge-scan-error';
        const label = verdict === 'CLEAN' ? 'clean' : verdict === 'INFECTED' ? 'infected' : 'error';
        // Same wording and badges as the Rejected files table, so "which side blocked this?"
        // reads identically in both.
        const stageLabel = e.stage === 'egress' ? 'egress (outbound)' : 'ingress (inbound)';
        const stageBadge = e.stage === 'egress' ? 'badge-out' : 'badge-in';
        return `
        <tr>
            <td class="ts" data-sort="${e.when}">${new Date(e.when).toLocaleString()}</td>
            <td>${escHtml(e.name)}</td>
            <td data-sort="${Number(e.size) || 0}">${fmtBytes(e.size)}</td>
            <td>${escHtml(e.origin)}</td>
            <td><span class="badge ${stageBadge}">${stageLabel}</span></td>
            <td><span class="badge ${badgeClass}">${label}</span></td>
            <td>${escHtml(e.detail)}</td>
        </tr>`;
    }).join('');
}

const REJECTION_REASON_LABELS = {
    EXTENSION_NOT_ALLOWED: 'extension not allowed',
    CONTENT_MISMATCH: 'content mismatch',
    HASH_MISMATCH: 'hash mismatch',
    AV_INFECTED: 'AV: infected',
    AV_ERROR: 'AV: scan error',
};
const REJECTION_REASON_BADGE = {
    EXTENSION_NOT_ALLOWED: 'badge-reject-policy',
    CONTENT_MISMATCH: 'badge-reject-security',
    HASH_MISMATCH: 'badge-reject-security',
    AV_INFECTED: 'badge-reject-security',
    AV_ERROR: 'badge-reject-policy',
};

function renderRejectedFiles(entries) {
    const tbody = document.getElementById('rejected-files-tbody');
    if (!tbody) return;
    if (entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty">No files rejected yet.</td></tr>';
        return;
    }
    // Server already returns newest-first; keep that order.
    tbody.innerHTML = entries.map(e => {
        const reason = String(e.reason || '');
        const reasonLabel = REJECTION_REASON_LABELS[reason] || reason.toLowerCase();
        const reasonBadge = REJECTION_REASON_BADGE[reason] || 'badge-reject-policy';
        const stageLabel = e.stage === 'egress' ? 'egress (outbound)' : 'ingress (inbound)';
        const stageBadge = e.stage === 'egress' ? 'badge-out' : 'badge-in';
        return `
        <tr>
            <td class="ts" data-sort="${e.when}">${new Date(e.when).toLocaleString()}</td>
            <td>${escHtml(e.name)}</td>
            <td data-sort="${e.size > 0 ? e.size : ''}">${e.size > 0 ? fmtBytes(e.size) : '—'}</td>
            <td>${escHtml(e.origin)}</td>
            <td><span class="badge ${stageBadge}">${stageLabel}</span></td>
            <td><span class="badge ${reasonBadge}">${escHtml(reasonLabel)}</span></td>
            <td>${escHtml(e.detail)}</td>
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
    save({ action: 'deny-route', domain: peerDomain, destination }, 'Route denied');
}

/** Lifts a route deny; the peer is asked to re-advertise so the route re-appears. */
function allowRoute(peerDomain, destination) {
    save({ action: 'allow-route', domain: peerDomain, destination }, 'Route allowed');
}

function renderUsersTab(cl) {
    renderLocalGroups();
    renderReceivedLists(cl.received || [], cl.localGroups || [], !!cl.groupsReadOnly);
    renderSentLists(cl.sent || []);
}

// A contact as "Name <jid>" (or just the JID when its server gave no name).
function contactChip(c) {
    const label = c.name
        ? `${escHtml(c.name)} <span style="color:#888">&lt;${escHtml(c.jid)}&gt;</span>`
        : escHtml(c.jid);
    return `<span style="display:inline-block;margin:2px 14px 2px 0;font-size:12px">${label}</span>`;
}

function contactChips(contacts, count) {
    if (!count) return '<span class="hint">No contacts.</span>';
    const more = count > contacts.length ? `<span class="hint">… and ${count - contacts.length} more</span>` : '';
    return contacts.map(contactChip).join('') + more;
}

// Keeps keyboard focus (and caret) on an input across an innerHTML re-render of its container.
function rememberFocus(container) {
    const a = document.activeElement;
    if (!a || !a.id || !container.contains(a)) return null;
    return { id: a.id, start: a.selectionStart, end: a.selectionEnd };
}

function restoreFocus(f) {
    if (!f) return;
    const el = document.getElementById(f.id);
    if (!el) return;
    el.focus();
    try { if (f.start != null) el.setSelectionRange(f.start, f.end); } catch (e) { /* not a text input */ }
}

// Hides the `.exposed-room` rows of a checklist that don't contain the query (case-insensitive).
function filterChecklist(listId, q) {
    const list = document.getElementById(listId);
    if (!list) return;
    const needle = (q || '').toLowerCase();
    list.querySelectorAll('.exposed-room').forEach(row => {
        row.style.display = row.textContent.toLowerCase().includes(needle) ? '' : 'none';
    });
}

// A checklist with a filter bar on top. `filters` remembers each list's query across re-renders.
function filteredChecklist(id, rowsHtml, placeholder, filters, emptyHtml) {
    const q = filters[id] || '';
    return `
        <input type="text" id="${id}-filter" class="list-search in-col" placeholder="${escHtml(placeholder)}"
               value="${escHtml(q)}" oninput="checklistFilters['${id}']=this.value;filterChecklist('${id}', this.value)">
        <div class="roomvis-list" id="${id}">${rowsHtml || emptyHtml}</div>`;
}
const checklistFilters = {};

// Re-applies every remembered checklist query (renders rebuild the rows unfiltered).
function reapplyChecklistFilters() {
    Object.entries(checklistFilters).forEach(([id, q]) => filterChecklist(id, q));
}

const expandedShares = new Set();   // groups whose settings panel is open
const editedShares = {};            // group -> Set of ticked servers not saved yet

function renderLocalGroups() {
    const tbody = document.getElementById('local-groups-tbody');
    if (!tbody) return;
    const cl = lastData.contactLists || {};
    const rules = cl.shareRules || [];
    // Every local group, plus any shared group Openfire no longer returns (so it can be unshared).
    const groups = new Set(cl.localGroups || []);
    rules.forEach(r => groups.add(r.group));
    const names = Array.from(groups).sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
    if (!names.length) {
        tbody.innerHTML = '<tr><td colspan="2" class="empty">No local groups — create one in Openfire to share its members.</td></tr>';
        return;
    }
    expandedShares.forEach(captureShareEdits);
    const focus = rememberFocus(tbody);

    tbody.innerHTML = names.map(g => {
        const rule = rules.find(r => r.group === g);
        const targets = rule ? rule.targets : [];
        const expanded = expandedShares.has(g);
        const sum = targets.length ? escHtml(targets.length <= 2 ? targets.join(', ') : targets.length + ' servers')
                                   : 'not shared';
        const missing = (cl.localGroups || []).includes(g) ? ''
            : ' <span class="badge badge-tls" title="Openfire no longer has this group">missing</span>';
        let row = `
        <tr>
            <td data-sort="${escHtml(g)}"><strong>${escHtml(g)}</strong>${missing}</td>
            <td class="room-detail-cell">
                <span class="room-detail-sum">${sum}</span>
                <button class="room-expand-btn ${expanded ? 'open' : ''}"
                        title="${expanded ? 'Hide' : 'Show'} sharing settings"
                        onclick="toggleShareDetail('${jsArg(g)}')">▸</button>
            </td>
        </tr>`;
        if (expanded) row += renderShareDetailRow(g, targets);
        return row;
    }).join('');
    reapplyChecklistFilters();
    restoreFocus(focus);
}

function renderShareDetailRow(group, targets) {
    const id = 'share-' + jidToElemId(group);
    const cur = editedShares[group] || new Set(targets);
    const cl = lastData.contactLists || {};
    const shareable = cl.shareableServers || [];
    const viaUntrusted = cl.untrustedPathServers || [];
    const servers = new Set(shareable);
    targets.forEach(t => servers.add(t));
    cur.forEach(t => servers.add(t));
    const rows = Array.from(servers).sort().map(s => {
        const pending = shareable.includes(s) ? ''
            : '<span class="badge badge-out" title="no usable route right now — sent when it is back">pending</span>';
        const edge = viaUntrusted.includes(s)
            ? '<span class="badge badge-untrusted" title="reached across an untrusted link — the server sending across it must allow sending contact lists on that link">via untrusted link</span>'
            : '';
        return `
            <label class="exposed-room">
                <input type="checkbox" class="share-cb" data-group="${escHtml(group)}" value="${escHtml(s)}"
                       ${cur.has(s) ? 'checked' : ''} onchange="captureShareEdits('${jsArg(group)}')">
                <span>${escHtml(s)}</span> ${pending}${edge}
            </label>`;
    }).join('');
    return `
    <tr class="detail-row exposed-editor-row room-detail-row">
        <td colspan="2">
            <div class="exposed-cols">
                <div class="exposed-col">
                    <div class="exposed-col-h">Share with
                        <span class="exposed-col-sub">ticked servers get every member of ${escHtml(group)} in their
                            contact list, kept in step with the group's membership. Untick a server and save to stop
                            sharing with it.</span>
                    </div>
                    ${filteredChecklist(id, rows, 'Filter servers…', checklistFilters,
                        '<p class="empty" style="margin:4px 0">No server can receive contact lists from here yet.</p>')}
                    <div style="margin-top:8px">
                        <button class="btn-small btn-primary" onclick="saveShare('${jsArg(group)}')">Save</button>
                    </div>
                </div>
            </div>
        </td>
    </tr>`;
}

function captureShareEdits(group) {
    const set = captureChecks(`.share-cb[data-group="${cssEscape(group)}"]`, editedShares[group]);
    if (set) editedShares[group] = set;
}

function toggleShareDetail(group) {
    toggleExpanded(expandedShares, group, captureShareEdits);
    renderLocalGroups();
}

function saveShare(group) {
    captureShareEdits(group);
    const targets = Array.from(editedShares[group] || []);
    const action = targets.length ? 'save-contact-share' : 'delete-contact-share';
    save({ action, group, targets: targets.join(',') }, targets.length ? 'Shared' : 'Sharing stopped')
        .then(result => { if (result && result.ok) delete editedShares[group]; });
}

function renderSentLists(sent) {
    const el = document.getElementById('contact-sent-container');
    if (!el) return;
    if (!sent.length) {
        el.innerHTML = '<p class="empty">No server receives a contact list from this server yet.</p>';
        return;
    }
    el.innerHTML = sent.map(t => {
        const state = !t.reachable
            ? '<span class="status-dot grey"></span> Unreachable — sent when it is back'
            : !t.delivered
                ? '<span class="status-dot orange"></span> Sending…'
                : t.crossesUntrusted
                    ? '<span class="status-dot orange"></span> Sent — crosses an untrusted link; arrives only if that link allows contact lists'
                    : '<span class="status-dot green"></span> Up to date';
        return card({
            header: `<strong>${escHtml(t.target)}</strong>
                <span class="peer-room-count">${t.count} contact(s)</span>
                <span class="hint" style="margin-left:auto">${state}</span>`,
            body: `<div style="padding:10px 14px">${contactChips(t.contacts, t.count)}</div>`,
        });
    }).join('');
}

function resendContactLists() {
    save({ action: 'resend-contact-lists' }, 'Re-sent');
}

// Unsaved mapping-form edits per origin, so a re-render (any status change) doesn't wipe them.
const contactMapEdits = {};

function renderReceivedLists(received, localGroups, readOnly) {
    const note = document.getElementById('contact-readonly-note');
    if (note) note.style.display = readOnly ? '' : 'none';
    const el = document.getElementById('contact-received-container');
    if (!el) return;
    if (!received.length) {
        el.innerHTML = '<p class="empty">No server shares contacts with this server yet. When a peer shares '
            + 'users or groups with you, its list appears here.</p>';
        return;
    }
    const focus = rememberFocus(el);
    el.innerHTML = received.map(r => renderReceivedList(r, localGroups, readOnly)).join('');
    reapplyChecklistFilters();
    restoreFocus(focus);
}

function renderReceivedList(r, localGroups, readOnly) {
    const o = r.origin, oj = jsArg(o), id = jidToElemId(o);
    const m = r.mapping;
    const edit = contactMapEdits[o];
    const display = edit ? edit.displayName : (m ? m.displayName : o);
    const groups = new Set(edit ? edit.groups : (m ? m.groups : []));
    const everybody = groups.has('*');

    const received = r.receivedAt
        ? 'received ' + new Date(r.receivedAt).toLocaleString()
        : 'not received since restart';
    const mapInfo = m ? '<span class="peer-map-count">mapped</span>' : '';
    const untrustedBadge = r.crossedUntrusted
        ? '<span class="badge badge-untrusted" title="An untrusted server relayed this list and could have altered it. '
          + 'Check the contacts before mapping it.">via untrusted link</span>' : '';
    const status = m
        ? `Openfire group <code>${escHtml(r.groupName)}</code>, shown to `
          + (m.groups.includes('*') ? 'all users' : escHtml(m.groups.join(', ')))
          + ` as <strong>${escHtml(m.displayName)}</strong>. Untick everything and save to unmap.`
        : 'Not mapped — no local user sees these contacts yet.';
    const error = r.error ? `<p class="hint" style="color:var(--red-ink);margin:6px 0 0">${escHtml(r.error)}</p>` : '';

    const listId = 'cmgroups-' + id;
    const groupRows = localGroups.map(g => `
        <label class="exposed-room">
            <input type="checkbox" class="cm-group" value="${escHtml(g)}" ${groups.has(g) ? 'checked' : ''}
                   ${everybody || readOnly ? 'disabled' : ''} onchange="captureContactMapEdit('${oj}')">
            <span>${escHtml(g)}</span>
        </label>`).join('');

    const contactRows = r.count === 0
        ? '<tr><td colspan="2" class="empty">This server shares no contacts with you right now.</td></tr>'
        : r.contacts.map(c => `<tr><td>${escHtml(c.name || '—')}</td><td><small>${escHtml(c.jid)}</small></td></tr>`).join('')
          + (r.count > r.contacts.length
              ? `<tr><td colspan="2" class="hint">… and ${r.count - r.contacts.length} more</td></tr>` : '');

    return card({
        list: 'contact-received', key: o, id: 'cm-' + id,
        header: `<span class="status-dot ${r.reachable ? 'green' : 'grey'}" title="${r.reachable ? 'reachable' : 'unreachable'}"></span>
            <strong>${escHtml(o)}</strong>
            <span class="peer-room-count">${r.count} contact(s)</span>
            ${mapInfo}
            ${untrustedBadge}
            <span class="hint" style="margin-left:auto">${received}</span>`,
        body: `
            <div class="exposed-cols" style="padding:12px 14px;align-items:flex-start">
                <div class="exposed-col" style="flex:0 0 300px">
                    <div class="exposed-col-h">Mapping
                        <span class="exposed-col-sub">${status}</span>
                    </div>
                    ${error}
                    <label style="display:flex;flex-direction:column;font-size:12px;color:#555;margin-bottom:8px">
                        Roster group name
                        <input type="text" id="cm-name-${id}" value="${escHtml(display)}" style="margin-top:3px"
                               oninput="captureContactMapEdit('${oj}')" ${readOnly ? 'disabled' : ''}>
                    </label>
                    <label class="exposed-room" style="font-weight:600">
                        <input type="checkbox" class="cm-all" ${everybody ? 'checked' : ''} ${readOnly ? 'disabled' : ''}
                               onchange="captureContactMapEdit('${oj}')">
                        <span>Show to all users</span>
                    </label>
                    ${filteredChecklist(listId, groupRows, 'Filter groups…', checklistFilters,
                        '<p class="empty" style="margin:4px 0">No local groups — create one in Openfire, or show the list to all users.</p>')}
                    <div style="margin-top:8px">
                        <button class="btn-small btn-primary" onclick="saveContactMapping('${oj}')" ${readOnly ? 'disabled' : ''}>
                            ${m ? 'Save' : 'Map'}</button>
                        ${edit ? `<button class="btn-small btn-ghost" style="margin-left:4px"
                                          onclick="discardContactMapEdit('${oj}')">Discard</button>` : ''}
                    </div>
                </div>
                <div class="exposed-col">
                    <div class="exposed-col-h">Contacts
                        <span class="exposed-col-sub">the users ${escHtml(o)} shares with this server</span>
                    </div>
                    <div class="roomvis-list" style="max-height:260px">
                        <table class="fed-table">
                            <thead><tr><th>Name</th><th>Address</th></tr></thead>
                            <tbody>${contactRows}</tbody>
                        </table>
                    </div>
                </div>
            </div>`,
    });
}

function readContactMapForm(origin) {
    const id = jidToElemId(origin);
    const card = document.getElementById('cm-' + id);
    if (!card) return null;
    const all = card.querySelector('.cm-all');
    const groups = all && all.checked
        ? ['*']
        : [...card.querySelectorAll('.cm-group:checked')].map(i => i.value);
    return { displayName: document.getElementById('cm-name-' + id).value, groups };
}

function captureContactMapEdit(origin) {
    const f = readContactMapForm(origin);
    if (!f) return;
    contactMapEdits[origin] = f;
    // "Show to all users" disables the per-group boxes; reflect that right away.
    const card = document.getElementById('cm-' + jidToElemId(origin));
    const all = card.querySelector('.cm-all').checked;
    card.querySelectorAll('.cm-group').forEach(i => { i.disabled = all; });
}

function discardContactMapEdit(origin) {
    delete contactMapEdits[origin];
    refresh();
}

function saveContactMapping(origin) {
    const f = readContactMapForm(origin);
    if (!f) return;
    if (!f.groups.length) {
        // Nothing ticked on a mapped list = unmap it; on an unmapped one there is nothing to save.
        if (lastMappingOf(origin)) { unmapContactList(origin); return; }
        alert('Choose at least one group, or Show to all users.');
        return;
    }
    save({ action: 'map-contact-list', origin, displayName: f.displayName.trim(), groups: f.groups.join('\n') },
         'Mapping saved')
        .then(result => { if (result && result.ok) delete contactMapEdits[origin]; });
}

function lastMappingOf(origin) {
    const received = (lastData.contactLists && lastData.contactLists.received) || [];
    const r = received.find(x => x.origin === origin);
    return r ? r.mapping : null;
}

function unmapContactList(origin) {
    save({ action: 'unmap-contact-list', origin }, 'Mapping removed')
        .then(result => { if (result && result.ok) delete contactMapEdits[origin]; });
}

function renderRoomDefaults(rules) {
    const tbody = document.getElementById('room-defaults-tbody');
    if (!tbody) return;
    if (!rules.length) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty">No rules — new rooms get no automatic federation settings.</td></tr>';
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
            <td data-sort="${r.federated ? 1 : 0}">${yn(r.federated)}</td>
            <td data-sort="${r.autoAccept ? 1 : 0}">${yn(r.autoAccept)}</td>
            <td>${escHtml(vis)}</td>
            <td data-sort="${r.autoMap ? 1 : 0}">${yn(r.autoMap)}</td>
            <td data-sort="${r.filesEnabled ? 1 : 0}">${yn(r.filesEnabled)}</td>
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
        save({
            action:     'save-room-default',
            pattern,
            federated:   document.getElementById('rd-federated').checked,
            autoAccept:  document.getElementById('rd-autoaccept').checked,
            autoMap:     document.getElementById('rd-automap').checked,
            filesEnabled: document.getElementById('rd-files').checked,
            visible:     document.getElementById('rd-visible').value.trim()
        }, 'Rule saved').then(result => {
            if (!result || !result.ok) return;
            document.getElementById('rd-pattern').value = '';
            document.getElementById('rd-visible').value = '';
            document.getElementById('rd-federated').checked = true;
            document.getElementById('rd-autoaccept').checked = false;
            document.getElementById('rd-automap').checked = false;
            document.getElementById('rd-files').checked = true;
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
    document.getElementById('rd-files').checked = r.filesEnabled !== false;   // default on
    document.getElementById('rd-visible').value =
        (r.visible && r.visible.length) ? r.visible.join(', ') : '';
    document.getElementById('rd-pattern').focus();
}

function deleteRoomDefault(pattern) {
    if (!confirm('Delete the rule for "' + pattern + '"?')) return;
    save({ action: 'delete-room-default', pattern }, 'Rule deleted');
}

function applyRoomDefaultsNow() {
    if (!confirm('Apply the default-settings rules to every existing room now?')) return;
    save({ action: 'apply-room-defaults-now' }, result => 'Applied to ' + (result.applied || 0) + ' room(s)');
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

// Text that depends on the current time is advanced once a second from timestamps set at render time.

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
    document.querySelectorAll('.mapping-dup[data-dup-at]').forEach(el => {
        const secs = Math.max(0, Math.round((Date.now() - parseInt(el.dataset.dupAt, 10)) / 1000));
        el.textContent = dupChipText(parseInt(el.dataset.dupCount, 10), secs);
    });
}

// A `.filter-input[data-target]` filters the rows or cards of the element with that id, and a
// `.count-chip[data-count-for]` shows its size; both are re-applied after every render.
const tableFilters = {};

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

/**
 * Renders rebuild rows and cards wholesale, so after any DOM change re-apply the active filters,
 * counts and sorts. Runs before the browser paints, so a rebuilt list never flashes unfiltered;
 * the changes it makes itself are dropped (takeRecords) so they don't trigger it again.
 */
function watchRenders() {
    const observer = new MutationObserver(() => {
        applyAllFilters();
        sortAllTables();
        observer.takeRecords();
    });
    observer.observe(document.body, { childList: true, subtree: true });
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

/** Filters top-level rows of a tbody; a `.detail-row` (editor, occupants) follows the row above it. */
function filterTbody(tbody, q) {
    let shown = 0, total = 0, lastVisible = true;
    tbody.querySelectorAll(':scope > tr').forEach(tr => {
        if (tr.querySelector('td.empty')) return;   // placeholder — leave untouched
        if (tr.classList.contains('detail-row')) {
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

// Clicking a `.fed-table` header cycles ascending, descending and original order; `data-sort`
// overrides a cell's text, detail rows move with their row, and each table's choice is remembered.
const SORT_STORE_KEY = 'federation.tableSort';
let tableSorts = {};

function bindTableSorting() {
    try { tableSorts = JSON.parse(localStorage.getItem(SORT_STORE_KEY)) || {}; } catch (e) { tableSorts = {}; }
    document.addEventListener('click', e => {
        const th = e.target.closest('.fed-table th.sortable');
        if (th) cycleSort(th);
    });
    sortAllTables();
}

function tableSortKey(table) {
    const tbody = table.tBodies[0];
    if (tbody && tbody.id) return tbody.id;
    return Array.from(table.querySelectorAll('thead th'), th => th.dataset.label || th.textContent.trim()).join('|');
}

function cycleSort(th) {
    const table = th.closest('table');
    const key = tableSortKey(table);
    const col = th.cellIndex;
    const cur = tableSorts[key];
    if (!cur || cur.col !== col) tableSorts[key] = { col, dir: 1 };
    else if (cur.dir === 1)      tableSorts[key] = { col, dir: -1 };
    else                         delete tableSorts[key];
    try { localStorage.setItem(SORT_STORE_KEY, JSON.stringify(tableSorts)); } catch (e) { /* storage blocked */ }
    sortAllTables();
}

/** Adds arrows to new headers and re-applies each table's sort. Cheap when nothing moved. */
function sortAllTables() {
    document.querySelectorAll('table.fed-table').forEach(table => {
        decorateSortHeaders(table);
        if (table.tBodies[0]) sortTable(table, tableSorts[tableSortKey(table)]);
    });
}

function decorateSortHeaders(table) {
    const key = tableSortKey(table);
    const st = tableSorts[key];
    table.querySelectorAll('thead th').forEach(th => {
        if (!th.classList.contains('sortable')) {
            const label = th.textContent.trim();
            if (!label || 'nosort' in th.dataset) return;
            th.dataset.label = label;
            th.classList.add('sortable');
            th.title = 'Sort by ' + label;
            const arrow = document.createElement('span');
            arrow.className = 'sort-arrow';
            th.appendChild(arrow);
        }
        const active = st && st.col === th.cellIndex;
        const arrow = th.querySelector('.sort-arrow');
        const glyph = active ? (st.dir === 1 ? '▲' : '▼') : '↕';
        if (arrow.textContent !== glyph) arrow.textContent = glyph;
        th.classList.toggle('sorted', !!active);
    });
}

function sortCellValue(tr, col) {
    const td = tr.cells[col];
    if (!td) return '';
    const v = ('sort' in td.dataset) ? td.dataset.sort : td.textContent;
    return v.trim();
}

function sortTable(table, st) {
    const tbody = table.tBodies[0];
    // Each group is a main row and the detail rows that follow it.
    const groups = [], pinned = [];
    for (const tr of tbody.rows) {
        const only = tr.cells.length === 1 ? tr.cells[0] : null;
        if (only && (only.classList.contains('empty') || only.classList.contains('hint'))) pinned.push(tr);
        else if (tr.classList.contains('detail-row') && groups.length) groups[groups.length - 1].rows.push(tr);
        else groups.push({ main: tr, rows: [tr] });
    }
    if (groups.length < 2 && !pinned.length) return;
    // The rendered order, so "unsorted" can restore it and ties stay stable.
    let next = groups.reduce((m, g) => Math.max(m, Number(g.main.dataset.sortOrig) || 0), 0);
    groups.forEach(g => { if (!g.main.dataset.sortOrig) g.main.dataset.sortOrig = String(++next); });
    const orig = g => Number(g.main.dataset.sortOrig);

    let cmp = (a, b) => orig(a) - orig(b);
    if (st) {
        const isBlank = v => v === '' || v === '—' || v === '-';
        const vals = new Map(groups.map(g => [g, sortCellValue(g.main, st.col)]));
        const numeric = groups.every(g => isBlank(vals.get(g)) || !isNaN(Number(vals.get(g))));
        cmp = (a, b) => {
            const va = vals.get(a), vb = vals.get(b);
            if (isBlank(va) || isBlank(vb)) return (isBlank(va) - isBlank(vb)) || orig(a) - orig(b); // blanks last
            const c = numeric ? Number(va) - Number(vb)
                              : va.localeCompare(vb, undefined, { numeric: true, sensitivity: 'base' });
            return (c * st.dir) || orig(a) - orig(b);
        };
    }
    const wanted = groups.slice().sort(cmp).flatMap(g => g.rows).concat(pinned);
    const current = Array.from(tbody.rows);
    if (wanted.every((tr, i) => tr === current[i])) return;
    // Moving a row drops focus from inputs inside it (a detail-row editor): put it back.
    const focused = document.activeElement;
    const sel = focused && tbody.contains(focused) ? [focused.selectionStart, focused.selectionEnd] : null;
    wanted.forEach(tr => tbody.appendChild(tr));
    if (sel && focused.isConnected && document.activeElement !== focused) {
        focused.focus();
        try { if (sel[0] != null) focused.setSelectionRange(sel[0], sel[1]); } catch (e) { /* not a text input */ }
    }
}

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
        <tr>
            <td data-sort="${escHtml(r.name || r.jid)}"><strong>${escHtml(r.name || r.jid)}</strong><br><small>${escHtml(r.jid)}</small>${renderRoomDuplicates(r)}</td>
            <td>${escHtml(r.description || '—')}</td>
            <td data-sort="${occList.length > 0 ? occList.length : (Number(r.occupants) || 0)}">${occCell}</td>
            <td data-sort="${r.federated ? 1 : 0}">
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

const expandedRoomOccupants = new Set();

function toggleRoomOccupants(jid) {
    toggleExpanded(expandedRoomOccupants, jid);
    renderLocalRooms(lastData.localRooms || []);
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
    return `<tr class="detail-row"><td colspan="5" style="background:#fafafa;padding:8px 24px">
        <div style="font-size:12px;color:#555;margin-bottom:4px">Occupants in ${escHtml(r.name || r.jid)} (local + federated):</div>
        ${items}
    </td></tr>`;
}

function setRoomFederated(jid, federated) {
    save({ action: 'set-room', jid, federated: federated.toString() });
}

function setRoomAutoAccept(jid, autoAccept) {
    save({ action: 'set-room-autoaccept', jid, autoAccept: autoAccept.toString() });
}

function setRoomFiles(jid, enabled) {
    save({ action: 'set-room-files', jid, filesEnabled: enabled.toString() });
}

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

/** "1h ago"-style age, readable from seconds to days. */
function dupAgeText(secs) {
    if (secs < 120) return `${secs}s ago`;
    if (secs < 7200) return `${Math.round(secs / 60)}m ago`;
    return `${Math.round(secs / 3600)}h ago`;
}

/**
 * Room-level warning, under the room's address so it is seen without expanding the room: copies of
 * traffic the room already had are arriving through its mappings, i.e. the rooms are mapped in a loop.
 * Totals every mapping; the tooltip names the ones bringing the copies in.
 */
function renderRoomDuplicates(r) {
    const looped = (r.mappings || []).filter(m => m.state === 'ACTIVE' && m.duplicates);
    if (looped.length === 0) return '';
    const count = looped.reduce((n, m) => n + m.dupCount, 0);
    const lastAgeSecs = Math.min(...looped.map(m => m.dupLastAgeSecs));
    const lastAt = Date.now() - lastAgeSecs * 1000;
    const through = looped
        .map(m => `  • ${escHtml(m.remoteRoomJid)}: ${m.dupCount} cop${m.dupCount === 1 ? 'y' : 'ies'}`)
        .join('&#10;');
    const title = `There seems to be a room loop. This room is reached by more than one path, so copies of `
        + `messages it already has keep arriving and are dropped. Nothing is lost, but every message also `
        + `travels the extra paths.&#10;&#10;`
        + `Look at this room's mappings (open it with ▸): the ones marked ⚠ are bringing the copies in.`
        + `&#10;${through}&#10;&#10;`
        + `Remove one of those mappings to break the loop. This warning clears 24 hours after the last copy.`;
    return `<div class="room-dup"><span class="mapping-dup" data-dup-at="${lastAt}" data-dup-count="${count}" `
        + `title="${title}">${dupChipText(count, lastAgeSecs)}</span></div>`;
}

/**
 * Warning chip for a mapping that is bringing in copies the room already has: its mappings form a
 * ring or mesh, so the room is reached by more than one path and the copies are dropped on arrival.
 */
function renderMappingDuplicates(m) {
    if (m.state !== 'ACTIVE' || !m.duplicates) return '';
    const lastAt = Date.now() - m.dupLastAgeSecs * 1000;
    const copies = m.dupCount === 1 ? '1 copy' : `${m.dupCount} copies`;
    const title = `There seems to be a room loop through this mapping. ${copies} of messages or presences `
        + `this room already had arrived through it from ${escHtml(m.remoteDomain)} and were dropped, the first `
        + `${dupAgeText(m.dupSinceAgeSecs)}. Nothing is lost, but every message also travels the extra path.`
        + `&#10;&#10;Look at this room's mappings: removing this one, or another mapping in the loop, breaks it. `
        + `This warning clears 24 hours after the last copy is dropped.`;
    return ` <span class="mapping-dup" data-dup-at="${lastAt}" data-dup-count="${m.dupCount}" title="${title}">`
        + `${dupChipText(m.dupCount, m.dupLastAgeSecs)}</span>`;
}

function dupChipText(count, lastAgeSecs) {
    return `⚠ ${count} duplicate${count === 1 ? '' : 's'} dropped · ${dupAgeText(lastAgeSecs)}`;
}

/** Renders one mapping row with a state badge and the buttons valid for that state. */
function renderMappingRow(localJid, m) {
    const dom = jsArg(m.remoteDomain);
    const lj = jsArg(localJid);
    const target = `<span class="badge badge-fed" title="${dom}">${escHtml(m.remoteRoomJid)}</span>`;
    const unmap = `<button class="btn-small btn-danger" onclick="unmapRoom('${lj}','${dom}')">Unmap</button>`;
    let badge, buttons, warning = '';
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
            warning = renderMappingDuplicates(m);
            buttons = `<button class="btn-small btn-warn" onclick="mappingAction('disable-mapping','${lj}','${dom}')">Disable</button> ${unmap}`;
            break;
        case 'PENDING_OUT':
            badge = `<span class="mapping-state" style="color:#856404">⧗ awaiting accept</span>`;
            buttons = `<button class="btn-small btn-ghost" onclick="mappingAction('','${lj}','${dom}',true)">Re-request</button> ${unmap}`;
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
            buttons = `<button class="btn-small btn-ghost" onclick="mappingAction('','${lj}','${dom}',true)">Re-request</button> ${unmap}`;
            break;
        default:
            badge = `<span class="mapping-state">${escHtml(m.state || '')}</span>`;
            buttons = unmap;
    }
    // Two lines: the mapping itself, then any warning under its room badge, so both stay visible.
    return `<div class="mapping-row mapping-row-stack">
                <div class="mapping-row-line">${target} ${badge} ${buttons}</div>
                ${warning ? `<div class="mapping-row-line">${warning}</div>` : ''}
            </div>`;
}

/** Generic mapping lifecycle action. When reRequest is true, re-sends the original map request. */
function mappingAction(action, localJid, remoteDomain, reRequest) {
    if (reRequest) {
        // Re-issue the request using the existing mapping's remote room (looked up from lastData).
        const room = (lastData.localRooms || []).find(x => x.jid === localJid);
        const m = room && (room.mappings || []).find(x => x.remoteDomain === remoteDomain);
        if (!m) return;
        save({ action: 'map-room', localJid, remoteJid: m.remoteRoomJid, remoteDomain }, 'Mapping re-requested');
        return;
    }
    save({ action, localJid, remoteDomain });
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

/** Full-width detail row behind the expand arrow: every federation setting for one room in one view. */
function renderRoomDetailRow(r) {
    const jid = jsArg(r.jid);
    const mappings = (r.mappings && r.mappings.length > 0)
        ? r.mappings.map(m => renderMappingRow(r.jid, m)).join('')
        : '<p class="empty" style="margin:6px 0">Not mapped yet — pick this room next to a remote room in the “Remote rooms” list below.</p>';
    return `
    <tr class="detail-row exposed-editor-row room-detail-row">
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
                    <label class="exposed-room">
                        <input type="checkbox" ${r.filesEnabled ? 'checked' : ''}
                               onchange="setRoomFiles('${jid}', this.checked)">
                        <span>Federate file sharing</span>
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
                <button class="btn-small btn-ghost" onclick="addRoomVisServer('${jsArg(r.jid)}')">Add</button>
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
    const set = captureChecks(`.roomvis-cb[data-jid="${cssEscape(jid)}"]`, editedRoomVis[jid]);
    if (set) editedRoomVis[jid] = set;
}

function toggleRoomDetail(jid) {
    toggleExpanded(expandedRoomVis, jid, captureRoomVisEdits);
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
    save({ action: 'set-room-visibility', jid, servers: Array.from(set).join(',') })
        .then(result => { if (result && result.ok) delete editedRoomVis[jid]; });
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
    save(params, 'Mapping removed');
}

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
                    <td data-sort="${escHtml(r.name || r.jid)}"><strong>${escHtml(r.name || r.jid)}</strong><br><small>${escHtml(r.jid)}</small></td>
                    <td>${escHtml(r.description || '—')}</td>
                    <td style="min-width:220px" data-sort="${mapped ? escHtml(mapped.localJid) : ''}">${mapCell}</td>
                </tr>`;
            }).join('');

        // Count active mappings for this peer (shown in header when collapsed).
        const activeMappings = Object.values(mappings)
            .flat()
            .filter(m => m.remoteDomain === peer).length;
        const mapInfo = activeMappings > 0
            ? `<span class="peer-map-count">${activeMappings} mapped</span>`
            : '';

        return card({
            list: 'remote-rooms', key: peer, startCollapsed: true,
            header: `<strong>${escHtml(peer)}</strong>
                <span class="count-chip" title="${rooms.length} federated room(s)">${rooms.length}</span>
                ${mapInfo}`,
            body: `
                <table class="fed-table">
                    <thead><tr><th>Remote room</th><th>Description</th><th>Local mapping</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>`,
        });
    }).join('');
}

function mapRoom(remoteJid, remoteDomain, selId) {
    const sel = document.getElementById(selId);
    if (!sel || !sel.value) {
        alert('Please select a local room to map to.');
        return;
    }
    save({ action: 'map-room', localJid: sel.value, remoteJid, remoteDomain }, 'Mapping requested');
}

// Whether each card the admin opened or closed is collapsed, keyed "list|key".
const cardState = new Map();

/**
 * A `.peer-section` card. With `list` and `key` its header opens and closes the body, and that
 * survives re-renders; without them it is a plain, always-open card.
 */
function card({ list, key, startCollapsed = false, id, header, body }) {
    const stateKey = list ? list + '|' + key : null;
    const collapsed = stateKey != null && (cardState.has(stateKey) ? cardState.get(stateKey) : startCollapsed);
    const attrs = (id ? ` id="${id}"` : '') + (stateKey ? ` data-card="${escHtml(stateKey)}"` : '');
    const head = stateKey
        ? `<div class="peer-section-header" onclick="toggleCard(this)"><span class="peer-collapse-icon"></span>`
        : `<div class="peer-section-header" style="cursor:default">`;
    return `
        <div class="peer-section${collapsed ? ' collapsed' : ''}"${attrs}>
            ${head}${header}</div>
            <div class="peer-section-body">${body}</div>
        </div>`;
}

function toggleCard(header) {
    const sec = header.closest('.peer-section');
    cardState.set(sec.dataset.card, sec.classList.toggle('collapsed'));
}

/** Opens or closes `key` in a Set of expanded rows; `capture` first keeps the edits of a panel being closed. */
function toggleExpanded(set, key, capture) {
    if (set.has(key)) {
        if (capture) capture(key);
        set.delete(key);
    } else {
        set.add(key);
    }
}

/**
 * Folds the checkboxes matching `selector` into `set` (a new Set when omitted): ticked values in,
 * unticked out. Returns the set, or null when none of those checkboxes is on screen.
 */
function captureChecks(selector, set) {
    const boxes = document.querySelectorAll(selector);
    if (boxes.length === 0) return null;
    set = set || new Set();
    boxes.forEach(b => { if (b.checked) set.add(b.value); else set.delete(b.value); });
    return set;
}

function jidToElemId(jid) {
    return jid.replace(/[^a-zA-Z0-9]/g, '_');
}

// Shows the "change applied" toast, which survives the re-render an inline badge would not.
let _toastTimer = null;
function flashSaved(label) {
    const t = document.getElementById('fed-toast');
    if (!t) return;
    t.textContent = '✓ ' + (label || 'Saved');
    t.classList.add('show');
    clearTimeout(_toastTimer);
    _toastTimer = setTimeout(() => t.classList.remove('show'), 2000);
}

/**
 * Posts an admin action and reports the outcome: a {ok} reply flashes `okLabel` (a string, or a
 * function of the reply), an {error} reply is shown in an alert, and either way the page refreshes
 * to the server's state. Resolves to the reply, so callers can drop pending edits once saved.
 */
function save(params, okLabel = 'Saved') {
    return post(params).then(result => {
        if (result && result.ok) flashSaved(typeof okLabel === 'function' ? okLabel(result) : okLabel);
        else if (result && result.error) alert(result.error);
        if (result) refresh();
        return result;
    });
}

function post(params) {
    // Double-submit CSRF: the cookie's token goes back as a parameter.
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

// Escapes a value for a single-quoted JS string inside a double-quoted inline handler. escHtml alone is
// not enough, since the browser decodes entities before the JS runs, so JS escaping comes first.
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
