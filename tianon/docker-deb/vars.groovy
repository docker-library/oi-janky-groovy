arches = [
	'amd64',
	'arm32v5',
	'arm32v7',
	'arm64v8',
	'i386',
	'ppc64le',
	's390x',
] as Set

suites = [
	'jessie',
	'stretch',

	'xenial',
	'zesty',
] as Set

exclusions = [
	'arm32v5': (suites - 'stretch'), // arm32v5 is slooooow, so save time by only building stretch
	'ppc64le': ['jessie'] as Set,
	's390x': ['jessie'] as Set,
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
