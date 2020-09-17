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
	'mips64le': suites - ['buster'], // Debian Buster is the only thing that can build successfully on mips64le right now
	'ppc64le': ['stretch'] as Set, // Debian Stretch is not supported on ppc64le anymore
	's390x': ['stretch'] as Set, // Debian Stretch is not supported on ppc64le anymore
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
