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

env.ARCH_BRANCH = 'dist-' + env.ACT_ON_ARCH

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
			git config user.email 'github+dockerlibrarybot@infosiftr.com'
		'''
	}

	dir('bb') { ansiColor('xterm') {
		stage('Prep') {
			sh '''
				git branch -D "$ARCH_BRANCH" || :
				git checkout -b "$ARCH_BRANCH" origin/master

				# convert "FROM debian:..." into "FROM arm32v7/debian:..." etc
				.github/workflows/fake-gsl.sh \\
					| awk -F ': ' '$1 == "Directory" { print $2 "/Dockerfile.builder" }' \\
					| xargs -rt sed -ri -e "s!^FROM !FROM $TARGET_NAMESPACE/!"

				# Debian Ports means unstable only
				if [ "$ACT_ON_ARCH" = 'riscv64' ]; then
					sed -ri -e 's!^(FROM [^:]+):[^-]+!\\1:unstable!' */uclibc/Dockerfile.builder
				fi
			'''
		}

		stage('Pull') {
			sh '''
				# gather a list of expected parents
				parents="$(
					.github/workflows/fake-gsl.sh \\
						| awk -F ': ' '$1 == "Directory" {
							print $2 "/Dockerfile"
							print $2 "/Dockerfile.builder"
						}' \\
						| xargs -r gawk 'toupper($1) == "FROM" { print $2 }' \\
						| sort -u \\
						| grep -vE '^scratch$|^'"$ACT_ON_IMAGE"'(:|$)'
				)"

				# pull the ones appropriate for our target architecture
				echo "$parents" \\
					| grep -E "^$TARGET_NAMESPACE/" \
					| xargs -rtn1 docker pull \\
					|| true
			'''
		}

		variants = sh(returnStdout: true, script: '''
			.github/workflows/fake-gsl.sh \\
				| awk -F ': ' '$1 == "Directory" { print $2 }'
		''').trim().tokenize()

		for (variant in variants) {
			withEnv(['variant=' + variant]) { stage(variant) {
				sh '''
					from="$(gawk 'toupper($1) == "FROM" { print $2 }' "$variant/Dockerfile.builder")"

					if ! docker inspect --type image "$from" > /dev/null 2>&1; then
						# skip anything we couldn't successfully pull/tag above
						# (deleting so that "./generate-stackbrew-library.sh" will DTRT)
						echo >&2 "warning: $variant is 'FROM $from', which failed to pull -- skipping"
						rm -rf "$variant"
						exit
					fi

					if ! ./build.sh "$variant"; then
						v="$(basename "$variant")" # "uclibc", "glibc", etc
						case "$ACT_ON_ARCH/$v" in
							# expected failures (missing toolchain support, etc)
							ppc64le/uclibc | s390x/uclibc)
								echo >&2 "warning: $variant failed to build (expected) -- skipping"
								rm -rf "$variant"
								exit
								;;
						esac

						echo >&2 "error: $variant failed to build"
						exit 1
					fi
				'''
			} }
		}

		stage('Commit') {
			sh '''
				git add -A .

				# set explicit timestamps to try to get 100% reproducible commit hashes (given a master commit we're based on)
				export GIT_AUTHOR_DATE="$(git log -1 --format='format:%aD' origin/master)"
				export GIT_COMMITTER_DATE="$GIT_AUTHOR_DATE"

				git commit --message "Build for $ACT_ON_ARCH"
			'''
		}

		sshagent(['docker-library-bot']) {
			stage('Push') {
				sh '''
					git push -f origin "$ARCH_BRANCH":"$ARCH_BRANCH"
				'''
			}
		}
	} }
}
