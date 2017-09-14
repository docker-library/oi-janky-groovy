// https://github.com/jenkinsci/pipeline-examples/blob/666e5e3f8104efd090e698aa9b5bc09dd6bf5997/docs/BEST_PRACTICES.md#groovy-gotchas
// tl;dr, iterating over Maps in pipeline groovy is pretty broken in real-world use
archesMeta = [
	['amd64', [:]],
	['arm32v5', [:]],
	['arm32v6', [:]],
	['arm32v7', [:]],
	['arm64v8', [:]],
	['i386', [:]],
	['ppc64le', [:]],
	['s390x', [:]],
	['windows-amd64', [:]],
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

def defaultImageMeta = [
	['arches', ['amd64'] as Set],
	['pipeline', 'multiarch/target-outdated-pipeline.groovy'],
]

imagesMeta = [:]

imagesMeta['alpine'] = [
	// TODO https://github.com/gliderlabs/docker-alpine/issues/304#issuecomment-315157660
	'arches': [
		// see http://dl-cdn.alpinelinux.org/alpine/edge/main/
		'amd64',
		'arm32v6',
		'arm64v8',
		'i386',
		'ppc64le',
		's390x',
	] as Set,
	'map': [
		// https://wiki.alpinelinux.org/wiki/Alpine_on_ARM
		// "Currently Alpine supports armv6/armhf arch"
		'amd64': 'x86_64',
		'arm32v6': 'armhf',
		'arm32v7': 'armhf', // Raspberry Pi, making life hard...
		'arm64v8': 'aarch64',
		'i386': 'x86',
		'ppc64le': 'ppc64le',
		's390x': 's390x',
	],
	'pipeline': 'multiarch/target-alpine-pipeline.groovy',
	'cron': '@weekly',
]
imagesMeta['bash'] = [
	// TODO https://github.com/tianon/docker-bash/pull/6
	'arches': imagesMeta['alpine']['arches'],
]
imagesMeta['docker'] = [
	// TODO remove this with official Alpine multiarch
	'arches': imagesMeta['alpine']['arches'],
	// let's just try all of them -- some will fail, and that's OK
]
imagesMeta['opensuse'] = [
	// TODO https://github.com/openSUSE/docker-containers-build/issues/22#issuecomment-309163169
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
	'cron': '@weekly',
]

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
	return arch.replaceAll(/^windows-/, 'win')
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

def _invokeWithContext(def context, Closure cl) {
	cl.resolveStrategy = Closure.DELEGATE_FIRST
	cl.delegate = context
	cl()
}

def prebuildSetup(context) {
	_invokeWithContext(context) {
		env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
		env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc

		env.TARGET_NAMESPACE = archNamespace(env.ACT_ON_ARCH)

		// we'll pull images explicitly -- we don't want it to ever happen _implicitly_ (since the architecture will be wrong if it does)
		env.BASHBREW_PULL = 'never'
	}
}

def seedCache(context) {
	_invokeWithContext(context) {
		dir(env.BASHBREW_CACHE) {
			// clear this directory first (since we "stash" it later, and want it to be as small as it can be for that)
			deleteDir()
		}

		stage('Seed Cache') {
			sh '''
				# ensure the bashbrew cache directory exists, and has an initialized Git repo
				bashbrew from --apply-constraints https://raw.githubusercontent.com/docker-library/official-images/master/library/hello-world > /dev/null

				# and fill it with our newly generated commit (so that "bashbrew build" can DTRT)
				git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:
			'''
		}
	}
}

def generateStackbrewLibrary(context) {
	context.stage('Generate') {
		sh '''
			mkdir -p "$BASHBREW_LIBRARY"
			./generate-stackbrew-library.sh > "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			bashbrew cat "$ACT_ON_IMAGE"
			bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
		'''
	}
}

def scrubBashbrewGitRepo(context) {
	context.stage('Scrub GitRepo') {
		sh '''#!/usr/bin/env bash
			set -Eeuo pipefail
			{
				echo 'GitRepo: https://doi-janky.infosiftr.net'
				bashbrew cat "$ACT_ON_IMAGE" | grep -vE '^Git(Repo|Fetch):'
			} > bashbrew-tmp
			mv -v bashbrew-tmp "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			bashbrew cat "$ACT_ON_IMAGE"
			bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
		'''
	}
}

def pullFakeFroms(context) {
	context.withEnv([
		'BASHBREW_FROMS_TEMPLATE=' + '''
			{{- range $.Entries -}}
				{{- if not ($.SkipConstraints .) -}}
					{{- $.DockerFrom . -}}
					{{- "\\n" -}}
				{{- end -}}
			{{- end -}}
		''',
	]) {
		stage('Pull') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail
				set -x

				# gather a list of expected parents
				parents="$(
					bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$ACT_ON_IMAGE" 2>/dev/null \\
						| sort -u \\
						| grep -vE '^$|^scratch$|^'"$ACT_ON_IMAGE"'(:|$)' \\
						|| true
				)"
				# all parents might be "scratch", in which case "$parents" will be empty

				if [ -n "$parents" ]; then
					# pull the ones appropriate for our target architecture
					echo "$parents" \\
						| awk -v ns="$TARGET_NAMESPACE" '{ if (/\\//) { print $0 } else { print ns "/" $0 } }' \\
						| xargs -rtn1 docker pull \\
						|| true

					# ... and then tag them without the namespace (so "bashbrew build" can "just work" as-is)
					echo "$parents" \\
						| awk -v ns="$TARGET_NAMESPACE" '!/\\// { print ns "/" $0; print }' \\
						| xargs -rtn2 docker tag \\
						|| true
				fi

				# if we can't fetch the tags from their real locations, let's try Tianon's "bad-ideas"
				if ! bashbrew from --uniq --apply-constraints "$ACT_ON_IMAGE"; then
					refsList="$(
						bashbrew list --uniq --apply-constraints "$ACT_ON_IMAGE" \\
							| sed \\
								-e 's!:!/!' \\
								-e "s!^!refs/tags/$ACT_ON_ARCH/!" \\
								-e 's!$!:!'
					)"
					[ -n "$refsList" ]
					git -C "${BASHBREW_CACHE:-$HOME/.cache/bashbrew}/git" \\
						fetch --no-tags \\
						https://github.com/tianon/bad-ideas.git \\
						$refsList

					bashbrew from --uniq --apply-constraints "$ACT_ON_IMAGE"
				fi
			'''

			// gather a list of tags for which we successfully fetched their FROM
			env.TAGS = sh(returnStdout: true, script: '''#!/usr/bin/env bash
				set -Eeuo pipefail

				# gather a list of tags we've seen (filled in build order) so we can catch "FROM $ACT_ON_IMAGE:xxx"
				declare -A seen=()

				tags="$(bashbrew list --apply-constraints --build-order --uniq "$ACT_ON_IMAGE")"
				for tag in $tags; do
					froms="$(bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$tag")"
					for from in $froms; do
						if [ "$from" = 'scratch' ]; then
							# scratch doesn't exist, but is permissible
							echo >&2 "note: '$tag' is 'FROM $from' (which is explicitly permissible)"
							continue
						fi

						if [ -n "${seen[$from]:-}" ]; then
							# this image is FROM one we're already planning to build, it's good
							continue
						fi

						if ! docker inspect --type image "$from" > /dev/null 2>&1; then
							# skip anything we couldn't successfully pull/tag above
							echo >&2 "warning: skipping '$tag' (missing 'FROM $from')"
							continue 2
						fi
					done

					echo "$tag"

					# add all aliases to "seen" so we can accurately collect things "FROM $ACT_ON_IMAGE:xxx"
					otherTags="$(bashbrew list "$tag")"
					for otherTag in $otherTags; do
						seen[$otherTag]=1
					done
				done
			''').trim()

			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail

				if [ -z "${TAGS:-}" ]; then
					echo >&2 'Error: none of the parents for the tags of this image could be fetched! (so none of them can be built)'
					exit 1
				fi
			'''
		}
	}
}

def createFakeBashbrew(context) {
	pullFakeFroms(context)

	context.stage('Fake It!') {
		sh '''#!/usr/bin/env bash
			{
				echo "Maintainers: Docker Library Bot <$ACT_ON_ARCH> (@docker-library-bot)"
				echo
				bashbrew cat $TAGS
			} > bashbrew-tmp
			set -x
			mv -v bashbrew-tmp "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			bashbrew cat "$ACT_ON_IMAGE"
			bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
		'''
	}
}

def bashbrewBuildAndPush(context) {
	// flat list of unique tags to try building (in build order)
	def tags = context.sh(returnStdout: true, script: '''
		bashbrew list --apply-constraints --build-order --uniq "$ACT_ON_IMAGE"
	''').tokenize()

	// list of tag alias lists which failed ([['latest', '1', '1.0', ...], ['0', '0.1', ...]])
	def failed = []
	// flat list of unique tags that succeeded
	def success = []

	for (tag in tags) { context.stage(tag) { withEnv(['tag=' + tag]) {
		def tagFrom = sh(returnStdout: true, script: '''
			bashbrew cat --format '{{- .DockerFrom .TagEntry -}}' "$tag"
		''').trim()

		def tagFailed = false
		if (failed.flatten().contains(tagFrom)) {
			tagFailed = true
		} else {
			withEnv(['tagFrom=' + tagFrom]) {
				tagFailed = (0 != sh(returnStatus: true, script: '''
					# pre-build sanity check
					docker inspect --type image "$tagFrom" > /dev/null

					# retry building each tag up to three times
					bashbrew build "$tag" || bashbrew build "$tag" || bashbrew build "$tag"
				'''))
			}
		}

		if (tagFailed) {
			// if this tag failed to build, record all aliases as failing
			failed << sh(returnStdout: true, script: '''
				bashbrew list "$tag"
			''').tokenize()
		} else {
			// otherwise, onwards and upwards!
			success << tag
		}
	} } }

	// add an extra "stage" with a summary so it's easier to figure out which tag caused the build to be unstable
	context.stage('Summary') {
		def summary = []

		if (success) {
			def successSummary = 'The following tags built successfully:\n\n'
			for (tag in success) {
				successSummary += '  - ' + tag + '\n'
			}
			summary << successSummary
		} else {
			summary << 'No tags built successfully! :('
		}

		if (failed) {
			def failedSummary = 'The following tags failed to build:\n\n'
			for (tagGroup in failed) {
				failedSummary += '  - ' + tagGroup[0] + '\n'
			}
			summary << failedSummary
		} else {
			summary << 'No tags failed to build! :D'
		}

		summary = summary.join('\n\n')
		if (failed && !success) {
			// if nothing succeeded, mark the build as a failure and abort
			error(summary)
		} else {
			if (failed) {
				// if we had partial success (not full), mark the build as unstable
				currentBuild.result = 'UNSTABLE'
			}
			echo(summary)
		}
	}

	def dryRun = false
	context.stage('Push') { withEnv(['tags=' + success.join(' ')]) {
		dryRun = sh(returnStdout: true, script: '''
			bashbrew push --dry-run --namespace "$TARGET_NAMESPACE" $tags

			if [ -n "$BASHBREW_ARCH" ]; then
				bashbrew --arch-namespace "$ACT_ON_ARCH = $TARGET_NAMESPACE" put-shared --dry-run --single-arch --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
			fi
		''').trim()

		if (dryRun != '') {
			retry(3) {
				sh '''
					bashbrew tag --namespace "$TARGET_NAMESPACE" $tags

					bashbrew push --namespace "$TARGET_NAMESPACE" $tags

					if [ -n "$BASHBREW_ARCH" ]; then
						bashbrew --arch-namespace "$ACT_ON_ARCH = $TARGET_NAMESPACE" put-shared --single-arch --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
					fi
				'''
			}
		} else {
			echo('Skipping unnecessary push!')
		}
	} }
	if (dryRun == '') {
		// if we didn't need to push anything let's tell whoever invoked us that we didn't, so they scan skip other things too (triggering children, for example)
		return 'skip'
	}
	return 'push'
}

def stashBashbrewBits(context) {
	_invokeWithContext(context) {
		if (env.BASHBREW_CACHE) {
			dir(env.BASHBREW_CACHE) {
				stash name: 'bashbrew-cache'
			}
		}
		if (env.BASHBREW_LIBRARY) {
			dir(env.BASHBREW_LIBRARY) {
				stash includes: env.ACT_ON_IMAGE, name: 'bashbrew-library'
			}
		}
	}
}

def unstashBashbrewBits(context) {
	_invokeWithContext(context) {
		if (env.BASHBREW_CACHE) {
			dir(env.BASHBREW_CACHE) {
				deleteDir()
				unstash 'bashbrew-cache'
			}
		}
		if (env.BASHBREW_LIBRARY) {
			dir(env.BASHBREW_LIBRARY) {
				deleteDir()
				unstash 'bashbrew-library'
			}
		}
	}
}

def docsBuildAndPush(context) {
	context.node(docsNode(context.env.ACT_ON_ARCH, context.env.ACT_ON_IMAGE)) {
		env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
		if (env.BASHBREW_CACHE) {
			env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
		}
		unstashBashbrewBits(this)

		stage('Checkout Docs') {
			checkout(
				poll: true,
				changelog: false,
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
						[
							$class: 'PathRestriction',
							excludedRegions: '',
							includedRegions: [
								env.ACT_ON_IMAGE + '/**',
								'*.pl',
								'*.sh',
								'.*/**',
							].join('\n'),
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
					# add a link to Jenkins build status
					cat >> .template-helpers/generate-dockerfile-links-partial.sh <<-EOSH
						cat <<-'EOBADGE'
							[![Build Status](${JOB_URL%/}/badge/icon) (\\`$TARGET_NAMESPACE/$ACT_ON_IMAGE\\` build job)](${JOB_URL})
						EOBADGE
					EOSH

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
				credentialsId: 'docker-hub-' + env.ACT_ON_ARCH.replaceAll(/^[^-]+-/, ''),
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

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
