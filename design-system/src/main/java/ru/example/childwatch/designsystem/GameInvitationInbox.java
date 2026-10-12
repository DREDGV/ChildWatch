package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only incoming invitations for the captured, authenticated phone owner. */
public final class GameInvitationInbox {
    public static final class Invitation {
        public final String id, type, name;
        public final long deadline;
        Invitation(String id, String type, String name, long deadline) {
            this.id=id; this.type=type; this.name=name; this.deadline=deadline;
        }
    }
    public static List<Invitation> parse(JSONObject response, String scope, long elapsed) {
        try {
            JSONArray identity=new JSONArray(scope);
            String family=identity.getString(1), member=identity.getString(2);
            if(family.isEmpty()||member.isEmpty()||!response.optBoolean("success"))return Collections.emptyList();
            long serverTime=response.optLong("serverTime",0);
            JSONArray rows=response.optJSONArray("games");
            List<Invitation> result=new ArrayList<>();
            if(rows==null)return result;
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.optJSONObject(i);
                if(row==null||!"INVITED".equals(row.optString("status"))||!"O".equals(row.optString("myMark"))
                    ||!family.equals(row.optString("familyId")))continue;
                JSONObject guest=row.optJSONObject("guest"),host=row.optJSONObject("host");
                if(guest==null||host==null||!member.equals(guest.optString("memberId")))continue;
                String id=row.optString("id"),type=row.optString("type");
                if(!id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")||!supported(type))continue;
                // Old servers still authenticate/expire the list; trust it for one refresh only.
                long remaining=serverTime>0?Math.min(900_000,row.optLong("expiresAt")-serverTime):15_000;
                if(remaining<=0)continue;
                String name=host.optString("displayName").trim();
                result.add(new Invitation(id,type,name.isEmpty()?"Участник семьи":name,elapsed+remaining));
            }
            return result;
        } catch(Exception invalid) { return Collections.emptyList(); }
    }
    public static boolean supported(String type) {
        return "BATTLESHIP".equals(type)||"SNAKE_COOP".equals(type)||"ARKANOID_COOP".equals(type);
    }
}
