// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

// setup environment variables, etc.
vars.prebuildSetup(this)

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/busybox.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
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
	}

	ansiColor('xterm') {
		dir('bb') {
			stage('Prep') {
				sh '''
					# TODO officially switch to Alpine 3.6 and remove this bit
					sed -ri -e 's!alpine:3.5!alpine:edge!g' musl/Dockerfile.builder
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

					# ... and then tag them without the namespace (so "bashbrew build" can "just work" as-is)
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
					git config user.name 'Docker Library Bot'
					git config user.email 'github+dockerlibrarybot@infosiftr.com'

					git add -A .
					git commit -m "Build for $ACT_ON_ARCH"
				'''
			}
			vars.seedCache(this)

			vars.generateStackbrewLibrary(this)
		}

		vars.createFakeBashbrew(this)
		vars.bashbrewBuildAndPush(this)

		vars.stashBashbrewBits(this)
	}
}

vars.docsBuildAndPush(this)
