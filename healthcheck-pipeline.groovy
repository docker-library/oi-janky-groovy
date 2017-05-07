properties([
	buildDiscarder(logRotator(daysToKeepStr: '14')),
	disableConcurrentBuilds(),
	pipelineTriggers([
		cron('H H * * H'),
	]),
])

node {
	stage('Checkout') {
		checkout(
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/healthcheck.git',
					name: 'origin',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'hc',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	dir('hc') {
		stage('Build') {
			sh '''
				ns='healthcheck'

				for image in */; do
					image="${image%/}"

					if ! grep -q "^FROM $image\$" "$image/Dockerfile"; then
						echo >&2 "error: '$image/Dockerfile' is not 'FROM $image'"
						exit 1
					fi

					( set -x && docker build --pull -t "$ns/$image" "$image" )

					for dockerfile in "$image"/Dockerfile.*; do
						[ -f "$dockerfile" ] || continue

						subImage="${dockerfile#$image/Dockerfile.}"
						if [ "$subImage" = 'alpine' ]; then
							subImage="$image:$subImage"
						fi

						if ! grep -q "^FROM $subImage\$" "$dockerfile"; then
							echo >&2 "error: '$dockerfile' is not 'FROM $subImage'"
							exit 1
						fi

						( set -x && docker build --pull -t "$ns/$subImage" -f "$dockerfile" "$image" )
					done
				done
			'''
		}
	}

	stage('Push') {
		sh '''
			docker images 'healthcheck/*' \
				| awk -F '  +' 'NR > 1 && $1 != "<none>" && $2 != "<none>" { print $1 ":" $2 }' \
				| xargs -rtn1 docker push
		'''
	}
}
