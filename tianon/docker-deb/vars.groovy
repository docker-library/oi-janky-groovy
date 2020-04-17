arches = [
	'amd64',
	'arm32v5',
	'arm32v7',
	'arm64v8',
	'i386',
	'mips64le',
	'ppc64le',
	's390x',
] as Set

debianSuites = [
	'buster',
	'stretch',
] as Set
ubuntuSuites = [
	'bionic',
	'xenial',
] as Set

suites = debianSuites + ubuntuSuites

exclusions = [
	'arm32v5': ubuntuSuites, // arm32v5 is slooooow, so save time by only building Debian
	'mips64le': ubuntuSuites, // our mips64le box is not especially speedy, so only build Debian there as well
	//'ppc64le': ['jessie'] as Set,
	//'s390x': ['jessie'] as Set,
]

// some arches need to sbuild their packages in a different environment than the final target
buildArch = [
	// $ schroot --help
	// Illegal instruction (core dumped)
	'arm32v5': 'arm32v7',
]

component = 'main'

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
