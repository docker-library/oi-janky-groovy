// https://github.com/jenkinsci/pipeline-examples/blob/666e5e3f8104efd090e698aa9b5bc09dd6bf5997/docs/BEST_PRACTICES.md#groovy-gotchas
// tl;dr, iterating over Maps in pipeline groovy is pretty broken in real-world use
archesMeta = [
	['amd64', [:]],
	['arm32v5', [:]],
	['arm32v6', [:]],
	['arm32v7', [:]],
	['arm64v8', [:]],
	['i386', [:]],
	['mips64le', [:]],
	['ppc64le', [:]],
	['riscv64', [:]],
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
	'mips64le': 'mips64el',
	'ppc64le': 'ppc64el',
	'riscv64': 'riscv64',
	's390x': 's390x',
]

// list of arches
arches = []

// given an arch, returns a target namespace
def archNamespace(arch) {
	return arch.replaceAll(/^windows-/, 'win')
}

// returns a value suitable for "env.BASHBREW_ARCH_NAMESPACES"
def archNamespaces() {
	def mappings = []
	for (arch in arches) {
		mappings << arch + ' = ' + archNamespace(arch)
	}
	return mappings.join(', ')
}

// given an arch/image combo, returns a target node expression
def node(arch, image) {
	switch (arch + ' ' + image) {
		case [
			'amd64 busybox-builder',
			'amd64 debuerreotype',
			//'i386 busybox-builder', // TODO figure out why this fails to build on many of our generic amd64 workers with what are pretty clearly libseccomp issues 😩
			//'i386 debuerreotype', // this fails on many of our builders thanks to https://github.com/debuerreotype/docker-debian-artifacts/issues/97
		]:
			return ''
	}

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

def bashbrewBuildAndPush(context) {
	// flat list of unique tags to try building (in build order)
	def tags = context.sh(returnStdout: true, script: '''
		bashbrew list --apply-constraints --build-order --uniq "$ACT_ON_IMAGE"
	''').tokenize()

	// list of tag alias lists which failed ([['latest', '1', '1.0', ...], ['0', '0.1', ...]])
	def failed = []
	// flat list of unique tags that failed
	def failedFlatList = []
	// flat list of unique tags that succeeded
	def success = []

	for (tag in tags) { context.stage(tag) { withEnv(['tag=' + tag]) {
		def tagFroms = sh(returnStdout: true, script: '''
			bashbrew cat --format '{{- .DockerFroms .TagEntry | join "\\n" -}}' "$tag" \\
				| sort -u
		''').trim()

		def tagFailed = false
		// https://stackoverflow.com/a/18534853
		def failedFroms = failed.flatten().intersect(tagFroms.tokenize())
		if (failedFroms) {
			// if any of our "FROM" images failed, we fail too
			tagFailed = true
			// "catchError" is the only way to set "stageResult" :(
			catchError(message: 'Build of "' + tag + '" failed: FROM failed: ' + failedFroms.join(', '), buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
				error()
			}
		} else {
			try {
				withEnv(['tagFroms=' + tagFroms]) {
					sh '''#!/usr/bin/env bash
						# pre-build sanity check (build context is definitely fetched, making sure all our FROMs exist locally)
						bashbrew fetch --arch-filter "$tag" # this already happened earlier, but doing it again as a sanity check
						for tagFrom in $tagFroms; do
							if [ "$tagFrom" = 'scratch' ]; then
								continue
							fi
							docker image inspect --format '.' "$tagFrom" > /dev/null
						done
					'''
				}

				// https://reproducible-builds.org/docs/source-date-epoch/
				// https://github.com/moby/buildkit/blob/2f27653c74dd57d26ff6474cd0635aced8e79765/docs/build-repro.md#source_date_epoch
				env.SOURCE_DATE_EPOCH = sh(returnStdout: true, script: '''#!/usr/bin/env bash
					set -Eeuo pipefail -x

					gitCache="$(bashbrew cat --format '{{ gitCache }}' "$tag")"
					commit="$(bashbrew cat --format '{{- .TagEntry.ArchGitCommit arch -}}' "$tag")"
					epoch="$(git -C "$gitCache" show --no-patch --format='format:%ct' "$commit")"
					[ -n "$epoch" ]
					echo "$epoch"
				''').trim()

				timeout(time: 3, unit: 'HOURS') {
					retry(3) { // retry building each tag up to three times (but still within the same shared timeout)
						sh 'bashbrew build "$tag"'
					}
				}
			} catch (err) {
				tagFailed = true
				// "catchError" is the only way to set "stageResult" :(
				catchError(message: 'Build of "' + tag + '" failed: ' + err, buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
					error()
				}
			}
		}

		if (tagFailed) {
			// if this tag failed to build, record all aliases as failing
			failed << sh(returnStdout: true, script: '''
				bashbrew list "$tag"
			''').tokenize()
			failedFlatList << failed[-1][0]
		} else {
			// otherwise, onwards and upwards!
			success << tag
		}
	} } }

	// add an extra "stage" with a summary so it's easier to figure out which tag caused the build to be unstable
	context.stage('Summary') {
		def summary = []

		if (failed) {
			def failedSummary = 'The following tags failed to build:\n\n'
			for (tagGroup in failed) {
				failedSummary += '  - ' + tagGroup[0] + '\n'
			}
			summary << failedSummary
		} else {
			summary << 'No tags failed to build! :D'
		}

		if (success) {
			def successSummary = 'The following tags built successfully:\n\n'
			for (tag in success) {
				successSummary += '  - ' + tag + '\n'
			}
			summary << successSummary
		} else {
			summary << 'No tags built successfully! :('
		}

		summary = summary.join('\n\n')
		echo(summary)
	}
	if (failed && !success) {
		// if nothing succeeded, mark the build as a failure and abort
		error()
	} else if (failed) {
		// if we had partial success (not full), mark the build as unstable
		currentBuild.result = 'UNSTABLE'
	}

	def dryRun = false
	withEnv(['TAGS=' + success.join(' '), 'FAILED=' + failedFlatList.join(' ')]) {
		context.stage('Tag') {
			sh '''
				bashbrew tag --target-namespace "$TARGET_NAMESPACE" $TAGS
			'''
		}

		context.stage('Build-Info') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail

				rm -rf build-info
				mkdir build-info
				git -C "$BASHBREW_LIBRARY" rev-parse HEAD > build-info/commit.txt
				mkdir build-info/image-ids
				for tag in ${TAGS:-}; do
					for alias in $(bashbrew list "$tag"); do
						docker image inspect --format '{{ .Id }}' "$TARGET_NAMESPACE/$tag" > "build-info/image-ids/${alias//:/_}.txt"
					done
				done
				echo "${TAGS:-}" | xargs -rn1 > build-info/success.txt
				echo "${FAILED:-}" | xargs -rn1 > build-info/failed.txt
			'''
			archiveArtifacts 'build-info/**'
		}

		context.withCredentials([string(credentialsId: 'dockerhub-public-proxy', variable: 'DOCKERHUB_PUBLIC_PROXY')]) {
			stage('Push') {
				dryRun = sh(returnStdout: true, script: '''
					bashbrew push --dry-run --target-namespace "$TARGET_NAMESPACE" $TAGS

					if [ -n "$BASHBREW_ARCH" ]; then
						bashbrew --arch-namespace "$ACT_ON_ARCH = $TARGET_NAMESPACE" put-shared --dry-run --single-arch --target-namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
					fi
				''').trim()

				if (dryRun != '') {
					retry(3) {
						sh '''
							bashbrew push --target-namespace "$TARGET_NAMESPACE" $TAGS

							if [ -n "$BASHBREW_ARCH" ]; then
								bashbrew --arch-namespace "$ACT_ON_ARCH = $TARGET_NAMESPACE" put-shared --single-arch --target-namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
							fi
						'''
					}
				} else {
					echo('Skipping unnecessary push!')
				}
			}
		}
	}
	if (dryRun == '') {
		// if we didn't need to push anything let's tell whoever invoked us that we didn't, so they scan skip other things too (triggering children, for example)
		return 'skip'
	}
	return 'push'
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
