import groovy.json.JsonSlurper

// Read-only during Gradle configuration. The safe wrapper reserves APK builds
// under its project mutex; sync, tests and compilation do not spend a number.
def baseCode = 2_000_000_000L
def maximumCode = 2_100_000_000L // Google Play/Android versionCode ceiling
def validCode = { Object value, String source ->
    String text = value?.toString()
    if (!(text ==~ /[0-9]+/)) throw new IllegalArgumentException("Invalid version code in ${source}")
    long code = Long.parseLong(text)
    if (code <= 0 || code > maximumCode) throw new IllegalArgumentException("Version code outside Android range in ${source}")
    code
}
def readState = { File root ->
    def json = new JsonSlurper()
    long issued = validCode(json.parse(new File(root, 'release-version.json')).minimumIssuedCode, 'release-version.json')
    long built = 0L
    long reserved = 0L
    def manifests = []
    def releases = new File(root, 'releases')
    if (releases.isDirectory()) releases.eachFileRecurse { if (it.name == 'manifest.json') manifests << it }
    def updates = new File(root, 'updates/manifest.json')
    if (updates.isFile()) manifests << updates
    manifests.each { file ->
        def apps = json.parse(file).apps
        ['parent', 'child'].each { name ->
            issued = Math.max(issued, validCode(apps?[(name)]?.versionCode, file.path + '/' + name))
        }
    }
    // Only APK output metadata, never source/build caches or diagnostic JSON.
    ['app/build/outputs/apk', 'parentwatch/build/outputs/apk', '.codex-build', 'artifacts/android'].each { path ->
        def directory = new File(root, path)
        if (directory.isDirectory()) directory.eachFileRecurse { file ->
            if (file.name == 'output-metadata.json' && file.path.replace('\\', '/').contains('/outputs/apk/')) {
                def data = json.parse(file)
                if (data.applicationId in ['ru.example.childwatch', 'ru.example.parentwatch', 'ru.example.parentwatch.debug']) {
                    data.elements.each { built = Math.max(built, validCode(it.versionCode, file.path)) }
                }
            }
        }
    }
    def reservation = new File(root, '.runtime/version-reservation.json')
    if (reservation.isFile()) reserved = validCode(json.parse(reservation).versionCode, reservation.path)
    [issued: issued, built: built, reserved: reserved]
}
def resolve = { Map state, long gitCount, Object requested ->
    long highest = [state.issued, state.built, state.reserved, baseCode + Math.max(0L, gitCount)].max()
    if (requested != null) {
        long code = validCode(requested, 'cwVersionCode')
        boolean retry = code == state.reserved && code > state.issued && code >= state.built
        if (code <= highest && !retry) throw new IllegalArgumentException("Requested version ${code} must exceed known ${highest}; only the latest un-staged reservation can be retried")
        return code
    }
    if (highest >= maximumCode) throw new IllegalArgumentException('Version code limit reached')
    highest + 1L
}
[readState: readState, resolve: resolve, validate: validCode]
