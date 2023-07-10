// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def infraVars = fileLoader.fromGit(
	'infra/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

properties([
	buildDiscarder(logRotator(numToKeepStr: '10')),
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
	//} else if (params.TARGET_NODE.startsWith('worker-')) { // TODO figure out what to _actually_ do for worker nodes (we can't "lock" them exclusively, and they don't have all the "FROM" checkout data because they don't usually need it)
	//	env.BASHBREW_ARCH = 'amd64'
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

		// TODO put this into a proper script somewhere 😅
		stage('Gather') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				oi-list() {
					local -
					set -Eeuo pipefail +x

					[ "$#" -gt 0 ] || set -- '--all'
					bashbrew list --repos "$@" | xargs -n1 -P"$(nproc)" bashbrew cat --format '
						{{- $ns := archNamespace arch -}}
						{{- range (.Entries | archFilter arch) -}}
							{{- range $.Tags "" false . -}}
								{{- . -}}{{- "\\n" -}}
								{{- if $ns -}}
									{{- $ns -}}/{{- . -}}{{- "\\n" -}}
								{{- end -}}
							{{- end -}}
							{{- $.DockerFroms . | join "\\n" -}}{{- "\\n" -}}
						{{- end -}}
					' | sed -r 's!:[^@]+@!@!' # handle ELK oddities
					bashbrew list --uniq "$@" | xargs -n1 -P"$(nproc)" bashbrew cat --format '
						{{- range (.Entries | archFilter arch) -}}
							{{- $.DockerCacheName . -}}{{- "\\n" -}}
						{{- end -}}
					' 2>/dev/null || :
				}

				docker-images() {
					local -
					set -Eeuo pipefail +x

					docker images --digests --no-trunc --format '
						{{- if (eq .Repository "<none>") -}}
							{{- .ID -}}
						{{- else -}}
							{{- .Repository -}}
							{{- if (eq .Tag "<none>") -}}
								{{- "@" -}}{{- .Digest -}}
							{{- else -}}
								{{- ":" -}}{{- .Tag -}}
							{{- end -}}
						{{- end -}}
					' "$@"
				}

				# list useless images (official-images that are no longer supported and other random images that are not necessary/useful)
				dlist() {
					local -
					set -Eeuo pipefail +x

					# get the list of things we know should exist (based on "bashbrew" metadata)
					oiList="$(oi-list)" || return 1

					# get the list of everything this daemon contains so we can cross-reference
					images="$(docker-images)" || return 1

					# exclude images we know we want to keep
					images="$(grep -vE '^(infosiftr/moby|oisupport/[^:@]+)(:|$)|^busybox:.+-builder$' <<<"$images")" || :

					# exclude images of running containers if running from an explicit tag
					containers="$(docker ps --no-trunc --format '{{ .Image }}' | awk '/[:@]/ { print; next } { print $0 ":latest" }')" || return 1
					images="$(comm -23 <(sort -u <<<"$images") <(sort -u <<<"$containers"))"

					comm -13 <(sort -u <<<"$oiList") <(sort -u <<<"$images")
				}

				dlist | tee ripe.txt

				wc -l ripe.txt
			'''
		}

		stage('Clean') {
			sh 'xargs -rt docker rmi < ripe.txt'
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
	}
}
