import groovy.json.JsonSlurper
def root = new File(args[0])
def fixture = new File(args[1])
def policy = evaluate(new File(root, 'scripts/version-policy.groovy'))
def cases = new JsonSlurper().parse(new File(fixture, 'vectors.json'))
cases.each { test ->
    def result = null
    boolean failed = false
    try { result = policy.resolve(test.state, test.state.gitCount as long, test.requested) }
    catch (IllegalArgumentException ignored) { failed = true }
    assert test.reject ? failed : (!failed && result == test.expected) : test.name
}
def state = policy.readState(fixture)
assert state == [issued:2000000301L, built:2000000350L, reserved:2000000352L]
assert policy.resolve(state, 0L, null) == 2000000353L
assert policy.validate('2000000288', 'inspection') == 2000000288L
new File(fixture, '.runtime/version-reservation.json').text = '{broken'
boolean failed = false
try { policy.readState(fixture) } catch (Exception ignored) { failed = true }
assert failed : 'Corrupt reservation ignored'
// Restore the fixture for the PowerShell corruption check, not production state.
new File(fixture, '.runtime/version-reservation.json').text = '{"versionCode":2000000352}'
println "PASS: ${cases.size()} Groovy policy vectors and discovery/inspection/corrupt-reservation checks"
