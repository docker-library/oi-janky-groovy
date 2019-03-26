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
//debuerreotypeVersion = '0.10' // TODO https://github.com/debuerreotype/debuerreotype/pull/57 O:)
debuerreotypeVersion = '4333d1933ccc205bab9c80b5aaf513b82c832fc2' // TODO https://github.com/debuerreotype/debuerreotype/pull/56

// build some arches explicitly elsewhere for speed/reliability
buildArch = [
	//'arm32v5': 'arm64v8',
	//'arm32v7': 'arm64v8',
]

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
