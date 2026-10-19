package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.FederatedRoomManager.VirtualOccupant;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationSender;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import org.dom4j.Element;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * Keeps the occupants of mapped rooms in step across servers: sends our occupants and the virtual
 * occupants we relay to each mapped room, passes leaves on, and evicts the virtual occupants whose
 * path was lost (docs/protocol/05).
 */
public final class OccupantSync {

    private static final Logger Log = LoggerFactory.getLogger(OccupantSync.class);

    private static final String NS_MUC_USER = "http://jabber.org/protocol/muc#user";

    private final FederationManager manager;

    OccupantSync(FederationManager manager) {
        this.manager = manager;
    }

    private FederatedRoomManager rooms() {
        return manager.getRoomManager();
    }

    private String nextHopToward(String domain) {
        return manager.getRoutingTable().findNextHop(domain).orElse(domain);
    }

    /**
     * Evicts, across all local rooms, every virtual occupant whose home server is {@code remoteDomain},
     * and passes each leave on to the room's other spokes.
     */
    public void evictAllVirtualOccupantsFromDomain(String remoteDomain) {
        for (String localJid : rooms().getRoomsWithAnyVirtualOccupants()) {
            List<VirtualOccupant> gone = rooms().removeVirtualOccupantsByOrigin(localJid, remoteDomain);
            announceEvictions(localJid, gone, VirtualOccupant::arrivedVia);
        }
    }

    /**
     * Cleans up after a mapping goes inactive. With no ACTIVE mapping left every virtual occupant
     * goes; otherwise only those that arrived through that mapping, and their leaves are passed on.
     */
    public void evictForInactiveMapping(String localRoomJid, String remoteDomain) {
        if (rooms().getMappingsForLocal(localRoomJid).isEmpty()) {
            evictAllVirtualOccupantsInRoom(localRoomJid);
            return;
        }
        List<VirtualOccupant> gone = rooms().removeVirtualOccupantsArrivedVia(localRoomJid, remoteDomain);
        announceEvictions(localRoomJid, gone, vo -> Set.of(remoteDomain));
    }

    /**
     * Evicts, across all local rooms, the virtual occupants that arrived through a mapped server that
     * just became unreachable, and passes the leaves on; their home server may still be reachable.
     */
    public void evictOccupantsArrivedViaLostServer(String lostDomain) {
        for (String localJid : rooms().getRoomsWithAnyVirtualOccupants()) {
            List<VirtualOccupant> gone = rooms().removeVirtualOccupantsArrivedVia(localJid, lostDomain);
            announceEvictions(localJid, gone, vo -> Set.of(lostDomain));
        }
    }

    /**
     * Evicts every virtual occupant of a room whose only reachable mapping was the lost server. This
     * catches occupants behind an untrusted edge, whose home servers are never routable here.
     */
    public void evictOccupantsForLostHub(String lostDomain) {
        for (String localJid : rooms().getRoomsWithAnyVirtualOccupants()) {
            List<RoomMapping> mappings = rooms().getMappingsForLocal(localJid);
            boolean mappedToLost = mappings.stream().anyMatch(m -> m.remoteDomain().equals(lostDomain));
            if (!mappedToLost) continue;
            boolean anyReachable = mappings.stream()
                    .anyMatch(m -> manager.getRoutingTable().isReachable(m.remoteDomain()));
            if (!anyReachable) {
                evictAllVirtualOccupantsInRoom(localJid);
            }
        }
    }

    /** Evicts every virtual occupant of a room, used when its last mapping goes. */
    public void evictAllVirtualOccupantsInRoom(String localRoomJid) {
        sendVirtualLeaves(localRoomJid, rooms().clearAllVirtualOccupants(localRoomJid));
    }

    /**
     * Shows evicted virtual occupants leaving to the room's local occupants, and passes each leave on
     * to the other mappings, excluding the paths {@code arrivedVia} gives for it.
     */
    private void announceEvictions(String localJid, List<VirtualOccupant> gone,
                                   Function<VirtualOccupant, Set<String>> arrivedVia) {
        if (gone.isEmpty()) return;
        sendVirtualLeaves(localJid, gone.stream().map(VirtualOccupant::nick).collect(Collectors.toSet()));
        for (VirtualOccupant vo : gone) {
            forwardVirtualLeave(localJid, vo.nick(), vo.origin(), arrivedVia.apply(vo));
        }
    }

    /** Sends an unavailable presence for each given virtual nick to every real local occupant. */
    private void sendVirtualLeaves(String localRoomJid, Set<String> nicks) {
        if (nicks.isEmpty()) return;
        try {
            JID localRoomJID = new JID(localRoomJid);
            MUCRoom room = manager.findLocalMucRoom(localRoomJid);
            if (room == null) return;
            Collection<MUCOccupant> occupants = room.getOccupants();
            if (occupants.isEmpty()) return;
            for (String nick : nicks) {
                JID virtualFrom = new JID(localRoomJID.getNode(), localRoomJID.getDomain(), nick);
                for (MUCOccupant occupant : occupants) {
                    Presence leave = new Presence();
                    leave.setFrom(virtualFrom);
                    leave.setTo(occupant.getUserAddress());
                    leave.setType(Presence.Type.unavailable);
                    Element item = leave.getElement().addElement("x", NS_MUC_USER).addElement("item");
                    item.addAttribute("affiliation", "none");
                    item.addAttribute("role", "none");
                    FederationStanzaFactory.markAsForwarded(leave);
                    FederationSender.directDeliver(leave);
                }
            }
            Log.debug("sendVirtualLeaves: sent {} leave(s) in {}", nicks.size(), localRoomJid);
        } catch (RuntimeException e) {
            Log.warn("Could not show {} virtual occupant(s) leaving {}", nicks.size(), localRoomJid, e);
        }
    }

    /**
     * Sends a join or leave presence for every occupant of {@code localRoom} to a mapped room. With
     * {@code markForwarded} false, the first join is left unmarked so the remote replies with its own roster once.
     */
    private void pushOccupants(String localRoom, String remoteDomain, String remoteRoomJid,
                               boolean leaving, boolean markForwarded) {
        try {
            MUCRoom room = manager.findLocalMucRoom(localRoom);
            if (room == null) return;
            Collection<MUCOccupant> occupants = room.getOccupants();
            if (occupants.isEmpty()) return;

            String localDomain = localDomain();
            String nextHop = nextHopToward(remoteDomain);
            String kind = leaving ? "leave" : "join";

            boolean syncTriggerSent = false;
            for (MUCOccupant occupant : occupants) {
                Presence p = new Presence();
                p.setFrom(occupant.getUserAddress());
                if (leaving) {
                    p.setType(Presence.Type.unavailable);
                } else {
                    Element curEl = occupant.getPresence().getElement();
                    Element showEl = curEl.element("show");
                    if (showEl != null) p.getElement().addElement("show").setText(showEl.getText());
                    for (Element statusEl : curEl.elements("status")) {
                        p.getElement().addElement("status").setText(statusEl.getText());
                    }
                }
                if (markForwarded || syncTriggerSent) FederationStanzaFactory.markAsForwarded(p);
                syncTriggerSent = true;
                FederationSender.send("occupant " + kind, remoteDomain, () -> FederationStanzaFactory.mucForward(
                        nextHop, remoteDomain, remoteRoomJid, localDomain, localDomain, p));
            }
            Log.debug("pushOccupants: sent {} {} for {} occupant(s) toward {} (fedOrigin={})",
                      kind, localRoom, occupants.size(), remoteRoomJid, markForwarded);
        } catch (RuntimeException e) {
            Log.warn("Could not push the occupants of {} to {}", localRoom, remoteRoomJid, e);
        }
    }

    /** Sends join or leave presences for our occupants to a mapped room, marked so no reverse sync follows. */
    public void pushVirtualPresences(String localRoom, String remoteDomain,
                                     String remoteRoomJid, boolean leaving) {
        pushOccupants(localRoom, remoteDomain, remoteRoomJid, leaving, true);
    }

    /** Sends our occupants' joins to a mapped room so that it replies once with its own occupants. */
    public void pushInitialSyncPresences(String localRoom, String remoteDomain, String remoteRoomJid) {
        pushOccupants(localRoom, remoteDomain, remoteRoomJid, false, false);
    }

    /** Sends our full roster of a room, local and virtual occupants, to a peer that just mapped it. */
    public void pushRosterToPeer(String localRoom, String toDomain, String remoteRoomJid) {
        pushOccupants(localRoom, toDomain, remoteRoomJid, false, true);
        forwardVirtualOccupants(localRoom, toDomain, remoteRoomJid);
    }

    /**
     * Sends the virtual occupants of {@code localRoom} to {@code toDomain}, except those that arrived
     * through it and its own users, so no one is echoed back the way they came.
     */
    public void forwardVirtualOccupants(String localRoom, String toDomain, String remoteRoomJid) {
        String localDomain = localDomain();
        String nextHop = nextHopToward(toDomain);
        for (VirtualOccupant vo : rooms().getVirtualOccupants(localRoom)) {
            if (vo.arrivedVia().contains(toDomain)) continue;
            if (toDomain.equals(vo.origin()))     continue;
            FederationSender.send("virtual occupant", toDomain, () -> {
                Presence vsync = new Presence();
                vsync.setFrom(new JID(vo.nick()));
                FederationStanzaFactory.markAsForwarded(vsync);
                return FederationStanzaFactory.mucForward(
                        nextHop, toDomain, remoteRoomJid, localDomain, localDomain, vsync);
            });
        }
    }

    /** Passes a virtual occupant's leave on to every mapping of the room, with the same exclusions as joins. */
    public void forwardVirtualLeave(String localRoom, String leavingNick, String origin, Set<String> arrivedVia) {
        String localDomain = localDomain();
        for (RoomMapping m : rooms().getMappingsForLocal(localRoom)) {
            String toDomain = m.remoteDomain();
            if (arrivedVia.contains(toDomain)) continue;
            if (toDomain.equals(origin))       continue;
            String nextHop = nextHopToward(toDomain);
            FederationSender.send("virtual occupant leave", toDomain, () -> {
                Presence vleave = new Presence();
                vleave.setFrom(new JID(leavingNick));
                vleave.setType(Presence.Type.unavailable);
                FederationStanzaFactory.markAsForwarded(vleave);
                return FederationStanzaFactory.mucForward(
                        nextHop, toDomain, m.remoteRoomJid(), localDomain, localDomain, vleave);
            });
        }
        Log.debug("forwardVirtualLeave: propagated leave of {} from {} (origin={}, arrivedVia={})",
                  leavingNick, localRoom, origin, arrivedVia);
    }

    /** Re-sends our occupants to every mapped room on destinations that just became reachable. */
    public void resyncMappedDestinations(Collection<String> destinations) {
        for (String dest : destinations) {
            if (!manager.getRoutingTable().isReachable(dest)) continue;
            for (List<RoomMapping> mappings : rooms().getLocalMappings().values()) {
                for (RoomMapping m : mappings) {
                    if (m.remoteDomain().equals(dest)) {
                        pushInitialSyncPresences(m.localRoomJid(), dest, m.remoteRoomJid());
                    }
                }
            }
        }
    }

    /**
     * Sends a local user's presence change to the mapped peers of every federated room they occupy.
     * A client's broadcast presence never reaches the interceptor, so this is the only path for it.
     */
    public void forwardLocalOccupantPresence(JID user, Presence presence) {
        if (user == null || user.getNode() == null) return;
        JID bareUser = user.asBareJID();
        for (String localRoomJid : rooms().getLocalMappings().keySet()) {
            List<RoomMapping> mappings = rooms().getMappingsForLocal(localRoomJid);
            if (mappings.isEmpty()) continue;
            MUCRoom room = manager.findLocalMucRoom(localRoomJid);
            if (room == null) continue;
            boolean present = room.getOccupants().stream().anyMatch(o ->
                    o.getUserAddress() != null && bareUser.equals(o.getUserAddress().asBareJID()));
            if (!present) continue;

            JID roomJid = new JID(localRoomJid);
            Presence fwd = new Presence();
            fwd.setFrom(bareUser);
            fwd.setTo(new JID(roomJid.getNode(), roomJid.getDomain(), bareUser.toString()));
            if (presence != null && presence.getType() == Presence.Type.unavailable) {
                fwd.setType(Presence.Type.unavailable);
            } else if (presence != null) {
                if (presence.getShow()   != null) fwd.setShow(presence.getShow());
                if (presence.getStatus() != null) fwd.setStatus(presence.getStatus());
            }
            for (RoomMapping m : mappings) {
                forwardMucPresence(fwd, m);
            }
        }
    }

    /** Wraps a room presence in a muc-forward to a mapped peer, as the interceptor does. */
    private void forwardMucPresence(Presence pres, RoomMapping mapping) {
        String localDomain  = localDomain();
        String remoteDomain = mapping.remoteDomain();
        String nextHop = nextHopToward(remoteDomain);
        FederationSender.send("occupant presence", remoteDomain, () -> FederationStanzaFactory.mucForward(
                nextHop, remoteDomain, mapping.remoteRoomJid(), localDomain, localDomain,
                new Presence(pres.getElement().createCopy())));
    }

    /** A room's occupants for the admin console: local occupants and remote virtual occupants with their presence. */
    public List<Map<String, String>> getRoomOccupants(String localRoomJid) {
        List<Map<String, String>> out = new ArrayList<>();
        MUCRoom room = manager.findLocalMucRoom(localRoomJid);
        if (room != null) {
            for (MUCOccupant occ : room.getOccupants()) {
                String show = "", status = "";
                Presence p = occ.getPresence();
                if (p != null) {
                    Element s  = p.getElement().element("show");
                    Element st = p.getElement().element("status");
                    if (s  != null) show   = s.getText();
                    if (st != null) status = st.getText();
                }
                out.add(occupantRow(occ.getNickname(),
                        occ.getUserAddress() != null ? occ.getUserAddress().toBareJID() : "", "local", show, status));
            }
        }
        for (Map.Entry<String, String> e : rooms().getVirtualOccupantPresence(localRoomJid).entrySet()) {
            String[] ps = e.getValue().split("\\|", 2);
            out.add(occupantRow(e.getKey(), e.getKey(), "remote",
                    ps.length > 0 ? ps[0] : "", ps.length > 1 ? ps[1] : ""));
        }
        return out;
    }

    private static Map<String, String> occupantRow(String name, String jid, String kind, String show, String status) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("jid", jid);
        m.put("kind", kind);
        m.put("show", show);
        m.put("status", status);
        return m;
    }
}
