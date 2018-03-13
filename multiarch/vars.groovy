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
	def targetNode = 'multiarch-' + arch

	switch (arch + ' ' + image) {
		case [
			'arm32v5 memcached', 'arm32v6 memcached', 'arm32v7 memcached', // https://github.com/docker-library/memcached/issues/25
			'arm32v6 busybox', https://github.com/docker-library/busybox/pull/41
			'arm32v6 golang', // https://github.com/docker-library/golang/issues/196
			'arm32v6 vault', // gpg: keyserver receive failed: End of file (same as "busybox")
		]:
			targetNode = 'multiarch-rpi2'
			break

		case [
			'amd64 debuerreotype',
			'i386 debuerreotype',
		]:
			targetNode = ''
			break
	}

	return targetNode
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
					[ "$tagFrom" = 'scratch' ] || docker inspect --type image "$tagFrom" > /dev/null

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
	context.stage('Push') { withEnv(['TAGS=' + success.join(' ')]) {
		sh '''
			bashbrew tag --namespace "$TARGET_NAMESPACE" $TAGS
		'''

		dryRun = sh(returnStdout: true, script: '''
			bashbrew push --dry-run --namespace "$TARGET_NAMESPACE" $TAGS

			if [ -n "$BASHBREW_ARCH" ]; then
				bashbrew --arch-namespace "$ACT_ON_ARCH = $TARGET_NAMESPACE" put-shared --dry-run --single-arch --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
			fi
		''').trim()

		if (dryRun != '') {
			retry(3) {
				sh '''
					bashbrew push --namespace "$TARGET_NAMESPACE" $TAGS

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

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
