// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
env.ACT_ON_ARCH = env.JOB_NAME.split('/')[-2] // "i386", etc

env.TARGET_NAMESPACE = vars.archNamespace(env.ACT_ON_ARCH)

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
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
		env.TAGS = sh(returnStdout: true, script: '#!/bin/bash -e' + '''
			# gather a list of tags we've seen (filled in build order) so we can catch "FROM $ACT_ON_IMAGE:xxx"
			declare -A seen=()

			for tag in $(bashbrew list --apply-constraints --build-order --uniq "$ACT_ON_IMAGE" 2>/dev/null); do
				for from in $(bashbrew cat -f "$BASHBREW_FROMS_TEMPLATE" "$tag" 2>/dev/null); do
					if [ "$from" = 'scratch' ]; then
						# scratch doesn't exist, but is permissible
						continue
					fi

					if [ -n "${seen[$from]:-}" ]; then
						# this image is FROM one we're already planning to build, it's good
						continue
					fi

					if ! docker inspect --type image "$from" > /dev/null 2>&1; then
						# skip anything we couldn't successfully pull/tag above
						continue 2
					fi
				done

				echo "$tag"

				# add all aliases to "seen" so we can accurately collect things "FROM $ACT_ON_IMAGE:xxx"
				for otherTag in $(bashbrew list "$tag"); do
					seen[$otherTag]=1
				done
			done
		''').trim()

		stage('Fake It!') {
			if (env.TAGS == '') {
				error 'None of the parents for the tags of this image could be fetched! (so none of them can be built)'
			}

			sh '''
				{
					echo "Maintainers: Docker Library Bot <$ACT_ON_ARCH> (@docker-library-bot)"
					echo
					bashbrew cat $TAGS
				} > "./$ACT_ON_IMAGE"
				mv -v "./$ACT_ON_IMAGE" "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
				bashbrew cat "$ACT_ON_IMAGE"
				bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
			'''
		}

		stage('Build') {
			retry(3) {
				sh '''
					bashbrew build "$ACT_ON_IMAGE"
				'''
			}
		}

		stage('Tag') {
			sh '''
				bashbrew tag --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
			'''
		}

		stage('Push') {
			sh '''
				bashbrew push --namespace "$TARGET_NAMESPACE" "$ACT_ON_IMAGE"
			'''
		}
	}
}
