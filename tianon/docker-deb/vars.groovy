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

component = 'main'

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
