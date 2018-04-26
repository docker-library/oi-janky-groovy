arches = [
	// see https://www.debian.org/ports/#portlist-released
	// see also https://lists.debian.org/debian-devel-announce/2016/10/msg00008.html ("Release Architectures for Debian 9 'Stretch'")
	'amd64',
	'arm32v5',
	'arm32v7',
	'arm64v8',
	'i386',
	'ppc64le',
	's390x',
] as Set

// https://github.com/debuerreotype/debuerreotype/releases
//debuerreotypeVersion = '0.6'
debuerreotypeVersion = 'deeb73d48e3f6b6a98b36308789170c4fc8067d9' // https://github.com/debuerreotype/debuerreotype/commit/dc4b7a412bac05d12dcbc3c370f39bcc226fa29c ("iputils-ping")

// build some arches explicitly elsewhere for speed/reliability
buildArch = [
	//'arm32v5': 'arm64v8',
	//'arm32v7': 'arm64v8',
]

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
