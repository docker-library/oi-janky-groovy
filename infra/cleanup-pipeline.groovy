// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def infraVars = fileLoader.fromGit(
	'infra/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

properties([
	// make sure there is enough for every arch triggered by an all-cleanup
	buildDiscarder(logRotator(numToKeepStr: (infraVars.archWorkers.size() * 2).toString())),
	disableResume(),
	parameters([
		choice(
			name: 'TARGET_NODE',
			choices: infraVars.archWorkers,
			description: 'which node to "cleanup" (list is hand-maintained 😭)',
		),
		string(
			name: 'BASHBREW_ARCH',
			defaultValue: '',
			description: 'will be auto-detected properly when unspecified (do not specify unless something is not working for some reason)',
			trim: true,
		),
	]),
	//pipelineTriggers([cron('H H * * 0')]), // this should be on the triggering job instead, not here
])

if (params.BASHBREW_ARCH) {
	env.BASHBREW_ARCH = params.BASHBREW_ARCH
} else {
	if (params.TARGET_NODE.startsWith('multiarch-')) {
		env.BASHBREW_ARCH = params.TARGET_NODE - 'multiarch-'
	} else if (params.TARGET_NODE.startsWith('windows-')) {
		env.BASHBREW_ARCH = 'windows-amd64' // TODO non-amd64??  match the other naming scheme, probably
	} else if (params.TARGET_NODE.startsWith('worker-')) {
		env.BASHBREW_ARCH = 'amd64' // all our workers are amd64 -- if that ever changes, we've probably got all kinds of assumptions we need to clean up! 😅
		// TODO ideally, this would somehow lock the *entire* worker instance until the cleanup job completes, but for now we'll have to live with slightly racy behavior (and rely on our other jobs being forgiving / able to do the correct thing on a re-run if they fail due to over-aggressive cleanup)
	} else {
		error('BASHBREW_ARCH not specified and unable to infer from TARGET_NODE (' + params.TARGET_NODE + ') 😞')
	}
}
echo('BASHBREW_ARCH: ' + env.BASHBREW_ARCH)

currentBuild.displayName = params.TARGET_NODE + ' <' + env.BASHBREW_ARCH + '> (#' + currentBuild.number + ')'

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def multiarchVars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

env.BASHBREW_ARCH_NAMESPACES = env.BASHBREW_ARCH + ' = ' + multiarchVars.archNamespace(env.BASHBREW_ARCH)

node(params.TARGET_NODE) {
	ansiColor('xterm') {
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
					],
					doGenerateSubmoduleConfigurations: false,
					submoduleCfg: [],
				],
			)
		}

		stage('Containers') {
			sh 'docker container prune --force'
		}

		stage('Volumes') {
			sh 'docker volume prune --all --force'
		}

		// TODO put these into proper scripts somewhere 😅
		stage('Gather') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				rm -f images-to-keep.txt

				# build up a file with a list of things we know we want/need to keep
				## all DOI tags
				bashbrew list --all | tee -a images-to-keep.txt
				## all "external pins"
				oi/.external-pins/list.sh | tee -a images-to-keep.txt
				## all container's images (normalized to either digest-only in "xxx:tag@digest" or ":latest" in "xxx")
				docker ps --all --no-trunc --format '{{ .Image }}' | awk '{ gsub(":[^@]+@", "@"); if (!/[:@]/) { $0 = $0 ":latest" }; print }' | tee -a images-to-keep.txt
			'''
		}

		stage('Filter') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				# gather the list of *all* images
				docker images --no-trunc --digests --format '{{ json . }}' > images.json

				# ... and post-process it to image ID + "references" so we can cross-reference our "images-to-keep.txt" list and end up with only a list of specific images safe to delete
				jq -s --rawfile keepRaw images-to-keep.txt '
					($keepRaw | rtrimstr("\n") | split("\n")) as $keep
					| reduce .[] as $i ({};
						.[$i.ID] += [ $i |
							$i.ID,
							if .Repository != "<none>" then
								if .Tag != "<none>" then .Repository + ":" + .Tag else empty end,
								if .Digest != "<none>" then .Repository + "@" + .Digest else empty end
							else empty end
						]
					)
					| del(.[] | select(.[] as $i | $keep | index($i)))
				' images.json | tee images-to-delete.json
			'''
		}

		stage('Clean') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				# now we're confident - delete! (via xargs so it can split into multiple commands for efficiency based on the number of images we need to delete)
				jq -r 'keys | join("\n")' images-to-delete.json | xargs -rt docker rmi -f
			'''
		}

		stage('BuildKit') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				docker builder prune --force
				docker builder prune --force --filter type=frontend --all

				json="$(oi/.bin/bashbrew-buildkit-env-setup.sh)"
				shell="$(jq <<<"$json" -r 'to_entries | map("export " + (.key + "=" + .value | @sh)) | join("\\n")')"
				eval "$shell"

				if [ -n "${BUILDX_BUILDER:-}" ]; then
					# again with "buildx" explicitly in case "bashbrew-buildkit-env-setup.sh" set BUILDX_BUILDER
					docker buildx prune --force
					docker buildx prune --force --filter type=frontend --all
				fi
			'''
		}

		// TODO somehow clean up BASHBREW_CACHE ?
	}
}
