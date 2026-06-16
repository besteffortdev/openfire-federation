/* federation.js — admin console client-side logic */

const API_URL = 'api';
const POLL_MS = 5000;

let pollTimer  = null;
let roomFilter = '';

// Tracks which peer sections are manually collapsed across refreshes.
const collapsedPeers = new Set();

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
            if (data.localDomain) {
                const el = document.getElementById('local-domain');
                if (el) el.textContent = data.localDomain;
            }
            const mappings   = data.mappings    || {};
            const localRooms = data.localRooms  || [];
            renderPeers(data.peers || []);
            renderS2SSessions(data.s2sSessions || []);
            renderRouting(data.routing || []);
            renderLocalRooms(localRooms);
            renderRemoteRooms(data.remoteRooms || {}, localRooms, mappings);
            updateStatusBadge(data.peers || []);
            updateKeepaliveInput(data.keepaliveSeconds);
            updateReconnectInput(data.reconnectSeconds);
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
    document.getElementById('form-add-peer').addEventListener('submit', e => {
        e.preventDefault();
        const domain = document.getElementById('input-peer-domain').value.trim();
        if (!domain) return;
        post({ action: 'add-peer', domain })
            .then(() => {
                document.getElementById('input-peer-domain').value = '';
                refresh();
            });
    });
}

function renderPeers(peers) {
    const tbody = document.getElementById('peers-tbody');
    if (peers.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="empty">No peers configured yet.</td></tr>';
        return;
    }
    const now = Date.now();
    tbody.innerHTML = peers.map(p => {
        const isWithdrawn = p.status === 'WITHDRAWN';
        const isUnreachable = p.status === 'UNREACHABLE';
        const needsAction = isWithdrawn || isUnreachable || p.status === 'UNKNOWN';
        const actionBtn = needsAction
            ? `<button class="btn-small btn-warn" style="margin-right:4px"
                       onclick="retryPeer('${escHtml(p.domain)}')">${isWithdrawn ? 'Reconnect' : 'Retry'}</button>`
            : '';

        let statusLabel = isWithdrawn ? 'Disconnected by remote' : p.status;
        if (isUnreachable) {
            const retryAt = p.nextRetryAt || 0;
            if (retryAt > now) {
                const secsLeft = Math.ceil((retryAt - now) / 1000);
                statusLabel += ` <span class="retry-countdown">next retry in ${secsLeft}s</span>`;
            } else {
                statusLabel += ` <span class="retry-countdown">retrying…</span>`;
            }
        }

        return `
        <tr>
            <td>${escHtml(p.domain)}</td>
            <td><span class="status-dot ${statusClass(p.status)}"></span> ${statusLabel}</td>
            <td>${p.lastSeen ? new Date(p.lastSeen).toLocaleString() : '—'}</td>
            <td style="white-space:nowrap">
                ${actionBtn}
                <button class="btn-small btn-danger"
                        onclick="removePeer('${escHtml(p.domain)}')">Remove</button>
            </td>
        </tr>`;
    }).join('');
}

function retryPeer(domain) {
    post({ action: 'retry-peer', domain }).then(refresh);
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

function removePeer(domain) {
    if (!confirm('Remove peer ' + domain + '?')) return;
    post({ action: 'remove-peer', domain }).then(refresh);
}

function statusClass(s) {
    if (s === 'REACHABLE')  return 'green';
    if (s === 'UNREACHABLE') return 'red';
    if (s === 'WITHDRAWN')   return 'orange';
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
    tbody.innerHTML = rooms.map(r => {
        let mappedCell;
        if (r.mappings && r.mappings.length > 0) {
            mappedCell = r.mappings.map(m => `
                <div class="mapping-row">
                    <span class="badge badge-fed" title="${escHtml(m.remoteDomain)}">${escHtml(m.remoteRoomJid)}</span>
                    <button class="btn-small btn-danger"
                            onclick="unmapRoom('${escHtml(r.jid)}','${escHtml(m.remoteDomain)}')">Unmap</button>
                </div>`).join('');
        } else {
            mappedCell = '<span style="color:#999;font-size:11px">not mapped</span>';
        }
        const visible = r.jid.toLowerCase().includes(roomFilter) ? '' : 'display:none';
        return `
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
            </td>
            <td>${mappedCell}</td>
        </tr>`;
    }).join('');
}

function setRoomFederated(jid, federated) {
    post({ action: 'set-room', jid, federated: federated.toString() }).then(refresh);
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
    const body = new URLSearchParams(params);
    return fetch(API_URL, { method: 'POST', body })
        .then(r => r.json())
        .catch(err => console.error('POST error:', err));
}

function escHtml(s) {
    if (s == null) return '';
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
