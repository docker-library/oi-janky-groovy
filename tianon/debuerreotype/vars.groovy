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
	's390x',
] as Set

// https://github.com/debuerreotype/debuerreotype/releases
debuerreotypeVersion = '0.11'

// TODO https://github.com/debuerreotype/debuerreotype/compare/master...tianon:examples
debuerreotypeExamplesCommit = 'cfb84b90f5e35297596150b62935fdb23f96ab1b'

// build some arches explicitly elsewhere for speed/reliability
buildArch = [
	//'arm32v5': 'arm64v8',
	//'arm32v7': 'arm64v8',
]

def parseTimestamp(context) {
	context.env.TZ = 'UTC'

	context.env.epoch = context.sh(returnStdout: true, script: 'date --date "$timestamp" +%s').trim()
	context.env.timestamp = '@' + context.env.epoch // now normalized!
	context.env.serial = context.sh(returnStdout: true, script: 'date --date "$timestamp" +%Y%m%d').trim()
	iso8601 = context.sh(returnStdout: true, script: 'date --date "$timestamp" --iso-8601=seconds').trim()

	context.currentBuild.displayName = context.env.serial + ' (#' + context.currentBuild.number + ')'
	context.currentBuild.description = '<code>' + context.env.timestamp + '</code><br /><code>' + iso8601 + '</code>'
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
