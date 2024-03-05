// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "amd64", "arm64v8", etc.
env.ACT_ON_IMAGE = 'busybox-builder'
env.TARGET_NAMESPACE = vars.archNamespace(env.ACT_ON_ARCH)

env.BASHBREW_ARCH = env.ACT_ON_ARCH
env.ARCH_BRANCH = 'dist-' + env.ACT_ON_ARCH
env.META_BRANCH = 'meta-' + env.ACT_ON_ARCH

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	stage('Checkout') {
		checkout(
			poll: true,
			changelog: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:docker-library/busybox.git',
					credentialsId: 'docker-library-bot',
					name: 'origin',
					refspec: '+refs/heads/master:refs/remotes/origin/master',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CloneOption',
						honorRefspec: true,
						noTags: true,
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'bb',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		sh '''
			cd bb
			git config user.name 'Docker Library Bot'
			git config user.email 'doi+docker-library-bot@docker.com'
		'''
	}

	dir('bb') { ansiColor('xterm') {
		env.variants = sh(returnStdout: true, script: '''
			.github/workflows/fake-gsl.sh \\
				| awk -F ': ' '$1 == "Directory" { print $2 }'
		''').trim()

		env.SOURCE_DATE_EPOCH = sh(returnStdout: true, script: '''#!/usr/bin/env bash
			set -Eeuo pipefail -x

			# convert "latest/uclibc" and friends into ":!latest/uclibc/*/**" to ignore per-architecture metadata for generating reproducible commit timestamps (that aren't dependent on arch-specific metadata)
			# https://stackoverflow.com/a/21079437/433558
			excludes="$(sed <<<"$variants" -e 's/^/:!/' -e 's!$!/*/**!')"

			git log -1 --format='format:%ct' -- $excludes
		''')

		stage('Munge') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				dirs=( $variants )

				if [ "$ACT_ON_ARCH" = 'riscv64' ]; then
					# no stable distro releases for riscv64 yet 👀
					./hack-unstable.sh "${dirs[@]}"

					# TODO figure out why the heck `tar -xf busybox.tar.bz2 -C /usr/src "busybox-$BUSYBOX_VERSION"` fails with a bunch of EPERM on faccessat2 the first time it runs, but succeeds the second time, and only on Alpine Edge on our riscv64 builder! 😭
					for dir in "${dirs[@]}"; do
						[[ "$dir" == *musl* ]] || continue
						sed -ri -e 's/^([[:space:]]*)(tar[[:space:]]+.*)(;[[:space:]]*\\\\)$/\\1\\2 || \\2\\3/' "$dir/Dockerfile.builder"
						git diff "$dir/Dockerfile.builder"
					done
				fi

				# convert "FROM debian:..." into "FROM arm32v7/debian:..." etc
				sed -ri -e "s!^FROM !FROM $TARGET_NAMESPACE/!" "${dirs[@]/%//Dockerfile.builder}"
			'''
		}

		stage('Pull') {
			sh '''#!/usr/bin/env bash
				set -Eeuo pipefail -x

				dirs=( $variants )

				# gather a list of expected parents
				parents="$(
					awk 'toupper($1) == "FROM" && $2 != "scratch" { print $2 }' \\
						"${dirs[@]/%//Dockerfile.builder}" \\
						"${dirs[@]/%//Dockerfile}" \\
						| sort -u
				)"

				# pull them all
				echo "$parents" \\
					| xargs -rtn1 docker pull \\
					|| true
			'''
		}

		def variants = env.variants.tokenize()
		for (variant in variants) {
			withEnv(['variant=' + variant]) { stage(variant) {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail -x

					from="$(awk 'toupper($1) == "FROM" { print $2; exit }' "$variant/Dockerfile.builder")" # TODO multi-stage?

					if ! docker image inspect --format '.' "$from" &> /dev/null; then
						# skip anything we couldn't successfully pull/tag above
						# (deleting so that "./generate-stackbrew-library.sh" will DTRT)
						echo >&2 "warning: $variant is 'FROM $from', which failed to pull -- skipping"
						git rm -rf --ignore-unmatch "$variant/$BASHBREW_ARCH"
						rm -rf "$variant/$BASHBREW_ARCH"
						exit
					fi

					if ! ./build.sh "$variant"; then
						v="$(basename "$variant")" # "uclibc", "glibc", etc
						case "$ACT_ON_ARCH/$v" in
							# expected failures (missing toolchain support, etc)
							ppc64le/uclibc | s390x/uclibc)
								echo >&2 "warning: $variant failed to build (expected) -- skipping"
								git rm -rf --ignore-unmatch "$variant/$BASHBREW_ARCH"
								rm -rf "$variant/$BASHBREW_ARCH"
								exit
								;;
						esac

						echo >&2 "error: $variant failed to build"
						exit 1
					fi
					git add -A "$variant/$BASHBREW_ARCH"
				'''
			} }
		}

		def changed = false
		stage('Diff') {
			changed = (
				0 != sh(
					returnStatus: true,
					script: '''
						git diff --staged --exit-code
					''',
				)
			)
		}

		if (changed) {
			stage('Commit') {
				sh '''
					# set explicit timestamps to try to get 100% reproducible commit hashes (given a master commit we're based on)
					GIT_AUTHOR_DATE="$(date --date "@$SOURCE_DATE_EPOCH" --rfc-email)"
					export GIT_AUTHOR_DATE GIT_COMMITTER_DATE="$GIT_AUTHOR_DATE"

					git branch -D "$META_BRANCH" || :
					git checkout -b "$META_BRANCH"

					# remove our tarballs so we can commit *just* the metadata to a separate branch (for consumption back to the main branch later)
					git rm --cached '**/*.tar*'

					if ! git diff --staged --name-only --exit-code; then
						git commit --message "Update metadata for $ACT_ON_ARCH"
					fi

					git branch -D "$ARCH_BRANCH" || :
					git checkout -b "$ARCH_BRANCH"

					# add back the tarballs 🚀 (which will also include our symlinks and `Dockerfile.builder` munging)
					git add -A .

					if ! git diff --staged --name-only --exit-code; then
						git commit --message "Builds for $ACT_ON_ARCH"
					fi
				'''
			}

			sshagent(['docker-library-bot']) {
				stage('Push') {
					sh '''
						git push -f origin "$META_BRANCH":"$META_BRANCH" "$ARCH_BRANCH":"$ARCH_BRANCH"
					'''
				}
			}
		}
	} }
}
