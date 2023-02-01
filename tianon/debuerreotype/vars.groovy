arches = [
	// see https://www.debian.org/ports/#portlist-released
	// see also https://lists.debian.org/debian-devel-announce/2016/10/msg00008.html ("Release Architectures for Debian 9 'Stretch'")
	'amd64',
	'arm32v5',
	'arm32v7',
	'arm64v8',
	'i386',
	'mips64le',
	'ppc64le',
	'riscv64',
	's390x',
] as Set

// https://github.com/debuerreotype/debuerreotype/releases
debuerreotypeVersion = '0.15'
debuerreotypeExamplesCommit = '0ced61869a33e5bc96b81ef26c3fa098d2f2c2e9' // to pull in a newer commit of examples/ (can be even with debuerreotypeVersion)
// https://github.com/debuerreotype/debuerreotype/commits/HEAD

// build some arches explicitly elsewhere for speed/reliability
buildArch = [
	//'arm32v5': 'arm64v8',
	//'arm32v7': 'arm64v8',
]

def parseTimestamp(context) {
	context.env.TZ = 'UTC'

	context.env.epoch = context.sh(returnStdout: true, script: 'date --utc --date "$timestamp" +%s').trim()
	context.env.timestamp = '@' + context.env.epoch // now normalized!
	context.env.serial = context.sh(returnStdout: true, script: 'date --utc --date "$timestamp" +%Y%m%d').trim()
	iso8601 = context.sh(returnStdout: true, script: 'date --utc --date "$timestamp" --iso-8601=seconds').trim()

	context.currentBuild.displayName = context.env.serial + ' (#' + context.currentBuild.number + ')'
	context.currentBuild.description = '<code>' + context.env.timestamp + '</code><br /><code>' + iso8601 + '</code>'
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
