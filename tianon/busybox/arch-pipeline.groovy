// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

env.ACT_ON_ARCH = env.JOB_BASE_NAME // "amd64", "arm64v8", etc.
env.ACT_ON_IMAGE = 'busybox'
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
			'''
		}

		stage('Pull') {
			sh '''
				# gather a list of expected parents
				parents="$(
					awk 'toupper($1) == "FROM" { print $2 }' */Dockerfile* \\
						| sort -u \\
						| grep -vE '/|^scratch$|^'"$ACT_ON_IMAGE"'(:|$)'
				)"

				# pull the ones appropriate for our target architecture
				echo "$parents" \\
					| awk -v ns="$TARGET_NAMESPACE" '{ print ns "/" $0 }' \\
					| xargs -rtn1 docker pull \\
					|| true

				# ... and then tag them without the namespace (so "./build.sh" can "just work" as-is)
				echo "$parents" \\
					| awk -v ns="$TARGET_NAMESPACE" '{ print ns "/" $0; print }' \\
					| xargs -rtn2 docker tag \\
					|| true
			'''
		}

		stage('Builders') {
			sh '''
				for builder in */Dockerfile.builder; do
					variant="$(basename "$(dirname "$builder")")"
					from="$(awk 'toupper($1) == "FROM" { print $2 }' "$builder")"

					if ! docker inspect --type image "$from" > /dev/null 2>&1; then
						# skip anything we couldn't successfully pull/tag above
						# (deleting so that "./generate-stackbrew-library.sh" will DTRT)
						rm -rf "$variant"
						continue
					fi

					if ! ./build.sh "$variant"; then
						echo >&2 "error: $variant failed to build -- skipping"
						rm -rf "$variant"
					fi
				done
			'''
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
