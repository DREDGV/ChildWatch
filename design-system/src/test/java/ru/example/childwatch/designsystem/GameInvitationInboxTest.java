package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameInvitationInboxTest {
    private final String scope="[\"server\",\"family\",\"child\",\"phone\"]";
    private JSONObject game() throws Exception {
        return new JSONObject().put("id","00000000-0000-0000-0000-000000000001")
            .put("familyId","family").put("type","ARKANOID_COOP").put("status","INVITED").put("myMark","O")
            .put("expiresAt",101_000).put("host",new JSONObject().put("displayName","Папа").put("memberId","parent"))
            .put("guest",new JSONObject().put("memberId","child"));
    }
    private JSONObject response(JSONObject g) throws Exception {
        return new JSONObject().put("success",true).put("serverTime",100_000).put("games",new JSONArray().put(g));
    }
    @Test public void serverClockIsConvertedToMonotonicDeadline() throws Exception {
        GameInvitationInbox.Invitation invite=GameInvitationInbox.parse(response(game()),scope,5000).get(0);
        assertEquals(6000,invite.deadline);assertEquals("Папа",invite.name);
    }
    @Test public void anotherFamilyOrOwnerOrOutgoingNeverAppears() throws Exception {
        assertTrue(GameInvitationInbox.parse(response(game().put("familyId","foreign")),scope,1).isEmpty());
        JSONObject other=game();other.getJSONObject("guest").put("memberId","friend");
        assertTrue(GameInvitationInbox.parse(response(other),scope,1).isEmpty());
        assertTrue(GameInvitationInbox.parse(response(game().put("myMark","X")),scope,1).isEmpty());
    }
    @Test public void closedExpiredUnknownAndMalformedRowsNeverAppear() throws Exception {
        assertTrue(GameInvitationInbox.parse(response(game().put("status","DECLINED")),scope,1).isEmpty());
        assertTrue(GameInvitationInbox.parse(response(game().put("expiresAt",100_000)),scope,1).isEmpty());
        assertTrue(GameInvitationInbox.parse(response(game().put("type","OTHER")),scope,1).isEmpty());
        assertTrue(GameInvitationInbox.parse(response(game().put("id","bad/identifier")),scope,1).isEmpty());
        assertTrue(GameInvitationInbox.parse(response(game()),"broken",1).isEmpty());
    }
    @Test public void legacyAuthenticatedListIsTrustedForOneRefreshOnly() throws Exception {
        JSONObject response=response(game());response.remove("serverTime");
        assertEquals(15_005,GameInvitationInbox.parse(response,scope,5).get(0).deadline);
    }
    @Test public void deadlineNeverExceedsServerInvitationTtl() throws Exception {
        assertEquals(900_005,GameInvitationInbox.parse(response(game().put("expiresAt",Long.MAX_VALUE)),scope,5).get(0).deadline);
    }
}
