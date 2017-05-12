// https://github.com/jenkinsci/pipeline-examples/blob/666e5e3f8104efd090e698aa9b5bc09dd6bf5997/docs/BEST_PRACTICES.md#groovy-gotchas
// tl;dr, iterating over Maps in pipeline groovy is pretty broken in real-world use
archesMeta = [
	//['amd64', [:]],
	//['arm32v5', [:]],
	//['arm32v6', [:]],
	//['arm32v7', [:]],
	//['arm64v8', [:]],
	['i386', [:]],
	['ppc64le', [:]],
	['s390x', [:]],
]
dpkgArches = [
	'amd64': 'amd64',
	'arm32v5': 'armel',
	'arm32v6': 'armhf', // Raspberry Pi, making life hard...
	'arm32v7': 'armhf',
	'arm64v8': 'arm64',
	'i386': 'i386',
	'ppc64le': 'ppc64el',
	's390x': 's390x',
]
apkArches = [
	'amd64': 'x86_64',
	'arm32v6': 'armhf',
	'arm32v7': 'armhf', // Raspberry Pi, making life hard...
	'arm64v8': 'aarch64',
	'i386': 'x86',
	'ppc64le': 'ppc64le',
	's390x': 's390x',
]

def defaultImageMeta = [
	['arches', ['amd64'] as Set],
	['pipeline', 'multiarch/target-generic-pipeline.groovy'],
]

imagesMeta = [:]

// base images (requires custom pipelines for now)
imagesMeta['alpine'] = [
	'arches': [
		// see http://dl-cdn.alpinelinux.org/alpine/edge/main/
		'amd64',
		'arm32v6',
		'arm64v8',
		'i386',
		'ppc64le',
		's390x',
	] as Set,
	'pipeline': 'multiarch/target-alpine-pipeline.groovy',
]
imagesMeta['debian'] = [
	'arches': [
		// see https://www.debian.org/ports/#portlist-released
		// see also https://lists.debian.org/debian-devel-announce/2016/10/msg00008.html ("Release Architectures for Debian 9 'Stretch'")
		'amd64',
		'arm32v5',
		'arm32v7',
		'arm64v8',
		'i386',
		'ppc64le',
		's390x',
	] as Set,
	'pipeline': 'multiarch/target-debian-pipeline.groovy',
]
imagesMeta['opensuse'] = [
	'arches': [
		// see http://download.opensuse.org/repositories/Virtualization:/containers:/images:/openSUSE-Tumbleweed/images/
		'amd64',
		'arm32v7',
		'arm64v8',
		'ppc64le',
		's390x',
	] as Set,
	'map': [
		// our-arch-name: opensuse-arch-name
		'amd64': 'x86_64',
		'arm32v7': 'armv7l',
		'arm64v8': 'aarch64',
		'ppc64le': 'ppc64le',
		's390x': 's390x',
	],
	'pipeline': 'multiarch/target-opensuse-pipeline.groovy',
]
imagesMeta['ubuntu'] = [
	'arches': [
		// see https://partner-images.canonical.com/core/xenial/current/
		'amd64',
		'arm32v7',
		'arm64v8',
		'i386',
		'ppc64le',
		's390x',
	] as Set,
	'pipeline': 'multiarch/target-ubuntu-pipeline.groovy',
]

// other images (whose "supported arches" lists are normally going to be a combination of their upstream image arches)
imagesMeta['bash'] = [
	'arches': imagesMeta['alpine']['arches'],
]
imagesMeta['buildpack-deps'] = [
	'arches': (imagesMeta['debian']['arches'] + imagesMeta['ubuntu']['arches']),
]
imagesMeta['busybox'] = [
	'arches': (imagesMeta['alpine']['arches'] + imagesMeta['debian']['arches'] + [
		// uClibc supported architectures
		// https://git.uclibc.org/uClibc/tree/extra/Configs
		'amd64',
		//'arm32v5', // ?
		//'arm32v6', // ?
		//'arm32v7', // ?
		//'arm64v8',
		//'i386',
		//'ppc64le',
		's390x',
	]),
	'pipeline': 'multiarch/target-busybox-pipeline.groovy',
]
// TODO "docker" (Alpine-only, needs 17.06); https://download.docker.com/linux/static/edge/
imagesMeta['gcc'] = [
	'arches': imagesMeta['debian']['arches'],
]
imagesMeta['golang'] = [
	'arches': (imagesMeta['alpine']['arches'] + [
		// https://golang.org/dl/
		// https://github.com/docker-library/golang/blob/master/update.sh ("dpkgArches")
		'amd64',
		'arm32v7', // "armv6l" binaries, but inside debian's "armhf"
		'i386',
		'ppc64le',
		's390x',
	]),
]
imagesMeta['postgres'] = [
	'arches': (imagesMeta['alpine']['arches'] + [
		// see http://apt.postgresql.org/pub/repos/apt/dists/jessie-pgdg/main/
		'amd64',
		'i386',
		'ppc64le',
	]),
]

// only debian and alpine variants
for (img in [
	'haproxy',
	'httpd',
	'memcached',
	'openjdk', 'tomcat',
	'php', 'wordpress',
	'python',
	//'rabbitmq', // TODO figure out erlang-solutions.com repo
	'redis',
	'ruby',
	'tomcat',
]) {
	imagesMeta[img] = [
		'arches': (imagesMeta['alpine']['arches'] + imagesMeta['debian']['arches']),
	]
}

// list of arches
arches = []
// list of images
images = []

// given an arch, returns a list of images
def archImages(arch) {
	ret = []
	for (image in images) {
		if (arch in imagesMeta[image]['arches']) {
			ret << image
		}
	}
	return ret as Set
}

// given an arch, returns a target namespace
def archNamespace(arch) {
	return arch
}

// given an arch/image combo, returns a target node expression
def node(arch, image) {
	return 'multiarch-' + arch
}

def docsNode(arch, image) {
	return ''
}

for (int i = 0; i < archesMeta.size(); ++i) {
	def arch = archesMeta[i][0]

	arches << arch
}
arches = arches as Set
for (image in imagesMeta.keySet()) {
	def imageMeta = imagesMeta[image]

	// apply "defaultImageMeta" for missing bits
	//   wouldn't it be grand if we could just use "map1 + map2" here??
	//   dat Jenkins sandbox...
	for (int j = 0; j < defaultImageMeta.size(); ++j) {
		def key = defaultImageMeta[j][0]
		def val = defaultImageMeta[j][1]
		if (imageMeta[key] == null) {
			imageMeta[key] = val
		}
	}

	images << image
	imagesMeta[image] = imageMeta
}
images = images as Set

def prebuildSetup(context) {
	context.env.ACT_ON_IMAGE = context.env.JOB_BASE_NAME // "memcached", etc
	context.env.ACT_ON_ARCH = context.env.JOB_NAME.split('/')[-2] // "i386", etc

	context.env.TARGET_NAMESPACE = archNamespace(context.env.ACT_ON_ARCH)
}

def bashbrewBuildAndPush(context) {
	context.stage('Build') {
		retry(3) {
			sh '''
				bashbrew build "$ACT_ON_IMAGE"
			'''
		}
	}

	context.stage('Tag') {
		sh '''
			bashbrew tag --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
		'''
	}

	context.stage('Push') {
		sh '''
			bashbrew push --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
		'''
	}
}

def stashBashbrewBits(context) {
	if (context.env.BASHBREW_CACHE) {
		context.dir(context.env.BASHBREW_CACHE) {
			stash name: 'bashbrew-cache'
		}
	}
	if (context.env.BASHBREW_LIBRARY) {
		context.dir(context.env.BASHBREW_LIBRARY) {
			stash includes: env.ACT_ON_IMAGE, name: 'bashbrew-library'
		}
	}
}

def unstashBashbrewBits(context) {
	if (context.env.BASHBREW_CACHE) {
		context.dir(context.env.BASHBREW_CACHE) {
			unstash 'bashbrew-cache'
		}
	}
	if (context.env.BASHBREW_LIBRARY) {
		context.dir(context.env.BASHBREW_LIBRARY) {
			unstash 'bashbrew-library'
		}
	}
}

def docsBuildAndPush(context) {
	context.node(docsNode(context.env.ACT_ON_ARCH, context.env.ACT_ON_IMAGE)) {
		docsEnvs = [
			'BASHBREW_LIBRARY=' + env.WORKSPACE + '/oi/library',
		]
		if (env.BASHBREW_CACHE) {
			docsEnvs << 'BASHBREW_CACHE=' + env.WORKSPACE + '/bashbrew-cache'
		}
		withEnv(docsEnvs) {
			unstashBashbrewBits(this)

			stage('Checkout Docs') {
				checkout(
					poll: true,
					scm: [
						$class: 'GitSCM',
						userRemoteConfigs: [[
							url: 'https://github.com/docker-library/docs.git',
						]],
						branches: [[name: '*/master']],
						extensions: [
							[
								$class: 'CleanCheckout',
							],
							[
								$class: 'RelativeTargetDirectory',
								relativeTargetDir: 'd',
							],
						],
						doGenerateSubmoduleConfigurations: false,
						submoduleCfg: [],
					],
				)
			}

			ansiColor('xterm') { dir('d') {
				stage('Update Docs') {
					sh '''
						./update.sh "$TARGET_NAMESPACE/$ACT_ON_IMAGE"
					'''
				}

				stage('Diff Docs') {
					sh '''
						git diff --color
					'''
				}

				withCredentials([[
					$class: 'UsernamePasswordMultiBinding',
					credentialsId: 'docker-hub-' + env.ACT_ON_ARCH,
					usernameVariable: 'USERNAME',
					passwordVariable: 'PASSWORD',
				]]) {
					stage('Push Docs') {
						sh '''
							dockerImage="docker-library-docs:$ACT_ON_ARCH-$ACT_ON_IMAGE"
							docker build --pull -t "$dockerImage" -q .
							test -t 1 && it='-it' || it='-i'
							set +x
							docker run "$it" --rm -e TERM \
								--entrypoint './push.pl' \
								"$dockerImage" \
								--username "$USERNAME" \
								--password "$PASSWORD" \
								--batchmode \
								"$TARGET_NAMESPACE/$ACT_ON_IMAGE"
						'''
					}
				}
			} }
		}
	}
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
