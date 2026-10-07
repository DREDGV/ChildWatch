package ru.example.childwatch.designsystem;

/** Explicit camera intent bound to a family member AND their current device. */
public final class MapFollowSelection {
    private String scope, member, device;

    public boolean start(String scope, String member, String device, long time, long now) {
        stop();
        if (scope == null || scope.isEmpty() || member == null || member.isEmpty()
                || device == null || device.isEmpty() || time <= 0 || time > now || now - time > 45_000) return false;
        this.scope = scope;
        this.member = member;
        this.device = device;
        return true;
    }
    public boolean matches(String scope, String member, String device) {
        return active() && this.scope.equals(scope) && this.member.equals(member) && this.device.equals(device);
    }
    public boolean refresh(String scope, String member, String device, long time, long now) {
        if (!matches(scope, member, device) || time <= 0 || time > now || now - time > 45_000) {
            stop();
            return false;
        }
        return true;
    }
    public boolean active() { return scope != null; }
    public void stop() { scope = member = device = null; }
}
