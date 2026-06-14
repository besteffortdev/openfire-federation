/* federation.js — admin console client-side logic */

const API_URL = 'api';
const POLL_MS = 5000;

let pollTimer  = null;
let roomFilter = '';

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
            renderLocalRooms(localRooms, mappings);
            renderRemoteRooms(data.remoteRooms || {}, localRooms, mappings);
            updateStatusBadge(data.peers || []);
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
    tbody.innerHTML = peers.map(p => {
        const retryBtn = (p.status === 'UNREACHABLE' || p.status === 'UNKNOWN')
            ? `<button class="btn-small btn-warn" style="margin-right:4px"
                       onclick="retryPeer('${escHtml(p.domain)}')">Retry</button>`
            : '';
        return `
        <tr>
            <td>${escHtml(p.domain)}</td>
            <td><span class="status-dot ${statusClass(p.status)}"></span> ${p.status}</td>
            <td>${p.lastSeen ? new Date(p.lastSeen).toLocaleString() : '—'}</td>
            <td style="white-space:nowrap">
                ${retryBtn}
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
    tbody.innerHTML = sessions.map(s => {
        const dirBadge = s.direction === 'outgoing'
            ? '<span class="badge badge-out">↑ outgoing</span>'
            : '<span class="badge badge-in">↓ incoming</span>';
        const fedBadge = s.fedPeer ? '<span class="badge badge-fed">federation</span>' : '';
        const tlsBadge = s.encrypted
            ? '<span class="badge badge-tls-ok">TLS</span>'
            : '<span class="badge badge-tls">plain</span>';
        return `
        <tr>
            <td><strong>${escHtml(s.domain)}</strong>${fedBadge}</td>
            <td>${dirBadge}</td>
            <td class="ts">${new Date(s.since).toLocaleString()}</td>
            <td>${tlsBadge}</td>
            <td>
                <button class="btn-small btn-danger"
                        onclick="killSession('${escHtml(s.domain)}','${escHtml(s.direction)}')">Kill</button>
            </td>
        </tr>`;
    }).join('');
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
    return s === 'REACHABLE' ? 'green' : s === 'UNREACHABLE' ? 'red' : 'grey';
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

// ── Local rooms tab ───────────────────────────────────────────────────────────

function renderLocalRooms(rooms, mappings) {
    const tbody = document.getElementById('local-rooms-tbody');
    if (rooms.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="empty">No local MUC rooms found.</td></tr>';
        return;
    }
    tbody.innerHTML = rooms.map(r => {
        const mapping = mappings[r.jid];
        let mappedCell;
        if (mapping) {
            mappedCell = `<span class="badge badge-fed" title="${escHtml(mapping.remoteDomain)}">${escHtml(mapping.remoteRoomJid)}</span>
                          <button class="btn-small btn-danger" style="margin-left:4px"
                                  onclick="unmapRoom('${escHtml(r.jid)}')">Unmap</button>`;
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

function unmapRoom(localJid) {
    if (!confirm('Remove mapping for ' + localJid + '?')) return;
    post({ action: 'unmap-room', localJid }).then(refresh);
}

// ── Remote rooms tab ──────────────────────────────────────────────────────────

function renderRemoteRooms(remoteRooms, localRooms, mappings) {
    const container = document.getElementById('remote-rooms-container');
    const peers = Object.keys(remoteRooms);
    if (peers.length === 0) {
        container.innerHTML = '<p class="empty">No remote rooms discovered yet. Enable a room for federation on a connected server — it will appear here automatically.</p>';
        return;
    }

    // Reverse index: remoteRoomJid → localJid (so we can show "already mapped" state)
    const reverseMap = {};
    for (const [localJid, m] of Object.entries(mappings)) {
        reverseMap[m.remoteRoomJid] = localJid;
    }

    // Local federated rooms that are not yet mapped — candidates for new mappings
    const mappedLocalJids = new Set(Object.keys(mappings));
    const available = localRooms.filter(r => r.federated && !mappedLocalJids.has(r.jid));

    container.innerHTML = peers.map(peer => {
        const rooms = remoteRooms[peer];
        const rows = rooms.length === 0
            ? '<tr><td colspan="3" class="empty">No federated rooms on this server.</td></tr>'
            : rooms.map(r => {
                const localJid = reverseMap[r.jid];
                let mapCell;
                if (localJid) {
                    mapCell = `<span class="badge badge-fed">↔ ${escHtml(localJid)}</span>
                               <button class="btn-small btn-danger" style="margin-left:4px"
                                       onclick="unmapRoom('${escHtml(localJid)}')">Unmap</button>`;
                } else if (available.length === 0) {
                    mapCell = '<span style="color:#999;font-size:11px">no local rooms available<br>(enable a room for federation first)</span>';
                } else {
                    const selId = jidToElemId(r.jid);
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

        return `
            <div class="peer-rooms">
                <h4>${escHtml(peer)}</h4>
                <table class="fed-table">
                    <thead><tr><th>Remote room</th><th>Description</th><th>Local mapping</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    }).join('');
}

function mapRoom(remoteJid, remoteDomain, selId) {
    const sel = document.getElementById(selId);
    if (!sel || !sel.value) {
        alert('Please select a local room to map to.');
        return;
    }
    post({ action: 'map-room', localJid: sel.value, remoteJid, remoteDomain }).then(refresh);
}

/** Converts a room JID into a safe HTML element id. */
function jidToElemId(jid) {
    return 'mapsel_' + jid.replace(/[^a-zA-Z0-9]/g, '_');
}

// ── Helpers ───────────────────────────────────────────────────────────────────

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
