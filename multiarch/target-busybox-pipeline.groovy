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

	ansiColor('xterm') { dir('bb') {
		stage('Prep') {
			sh '''
				sed -ri -e 's! --pull ! !g' build.sh

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

		stage('Generate') {
			sh '''
				mkdir -p "$BASHBREW_LIBRARY"
				./generate-stackbrew-library.sh > "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
			'''
		}
		stage('Seed Cache') {
			sh '''
				# ensure the bashbrew cache directory exists, and has an initialized Git repo
				bashbrew from https://raw.githubusercontent.com/docker-library/official-images/master/library/hello-world > /dev/null

				# and fill it with our newly generated commit (so that "bashbrew build" can DTRT)
				git -C "$BASHBREW_CACHE/git" fetch "$PWD" HEAD:
			'''
		}

		// gather a list of tags
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

		dir(env.BASHBREW_LIBRARY) {
			stash includes: env.ACT_ON_IMAGE, name: 'library'
		}
	} }
}

node('') {
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'
	dir(env.BASHBREW_LIBRARY) {
		unstash 'library'
	}

	stage('Checkout Docs') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/docs.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'd',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') { dir('d') {
		stage('Update Docs') {
			sh '''
				./update.sh "$TARGET_NAMESPACE/$ACT_ON_IMAGE"
			'''
		}

		stage('Diff Docs') {
			sh '''
				git diff --color
			'''
		}

		withCredentials([[
			$class: 'UsernamePasswordMultiBinding',
			credentialsId: 'docker-hub-' + env.ACT_ON_ARCH,
			usernameVariable: 'USERNAME',
			passwordVariable: 'PASSWORD',
		]]) {
			stage('Push Docs') {
				sh '''
					dockerImage="docker-library-docs:$ACT_ON_ARCH-$ACT_ON_IMAGE"
					docker build --pull -t "$dockerImage" -q .
					test -t 1 && it='-it' || it='-i'
					set +x
					docker run "$it" --rm -e TERM \
						--entrypoint './push.pl' \
						"$dockerImage" \
						--username "$USERNAME" \
						--password "$PASSWORD" \
						--batchmode \
						"$TARGET_NAMESPACE/$ACT_ON_IMAGE"
				'''
			}
		}
	} }
}
