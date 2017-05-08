env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc

env.TARGET_NAMESPACE = env.ACT_ON_ARCH // TODO possibly parameterized based on vars.archesMeta

targetNode = 'multiarch-' + env.ACT_ON_ARCH // TODO possibly parameterized

node(targetNode) {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/official-images.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'library/' + env.ACT_ON_IMAGE,
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	env.BASHBREW_FROMS_TEMPLATE = '''
		{{- range $.Entries -}}
			{{- if not ($.SkipConstraints .) -}}
				{{- $.DockerFrom . -}}
				{{- "\\n" -}}
			{{- end -}}
		{{- end -}}
	'''

	ansiColor('xterm') {
		stage('Pull') {
			sh '''
				# gather a list of expected parents
				parents="$(
					bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$ACT_ON_IMAGE" 2>/dev/null \\
						| sort -u \\
						| grep -vE '/|^scratch$|^'"$ACT_ON_IMAGE"'(:|$)'
				)"

				# pull the ones appropriate for our target architecture
				echo "$parents" \\
					| awk -v ns="$TARGET_NAMESPACE" '{ print ns "/" $0 }' \\
					| xargs -rtn1 docker pull \\
					|| true

				# ... and then tag them without the namespace (so "bashbrew build" can "just work" as-is)
				echo "$parents" \\
					| awk -v ns="$TARGET_NAMESPACE" '{ print ns "/" $0; print }' \\
					| xargs -rtn2 docker tag \\
					|| true
			'''
		}

		// gather a list of tags for which we successfully fetched their FROM
		// TODO handle images that are "FROM $ACT_ON_IMAGE:xxx"
		env.TAGS = sh(returnStdout: true, script: '#!/bin/bash -e' + '''
			for tag in $(bashbrew list --apply-constraints --uniq "$ACT_ON_IMAGE" 2>/dev/null); do
				for from in $(bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$tag" 2>/dev/null); do
					if [ "$from" = 'scratch' ]; then
						# scratch doesn't exist, but is permissible
						continue
					fi
					if ! docker inspect --type image "$from" > /dev/null 2>&1; then
						# skip anything we couldn't successfully pull/tag above
						continue 2
					fi
				done
				echo "$tag"
			done
		''').trim()

		if (env.TAGS == '') {
			error 'None of the parents for the tags of this image could be fetched! (so none of them can be built)'
		}

		stage('Build') {
			retry(3) {
				sh '''
					bashbrew build $TAGS
				'''
			}
		}

		stage('Tag') {
			sh '''
				bashbrew tag --namespace "$TARGET_NAMESPACE" $TAGS
			'''
		}

		stage('Push') {
			sh '''
				bashbrew push --namespace "$TARGET_NAMESPACE" $TAGS
			'''
		}
	}
}
