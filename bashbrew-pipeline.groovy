node {
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
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'bashbrew/go',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
		stage('Build') {
			sh '''
				docker build -t bashbrew --pull -q oi
				docker build -t bashbrew:cross - <<-'EODF'
					FROM bashbrew
					WORKDIR bashbrew/go
					RUN set -ex \\
						&& export GOPATH="$PWD:$PWD/vendor" \\
						&& export CGO_ENABLED=0 \\
						&& rm -r bin \\
						&& mkdir bin \\
						&& export arch='amd64' \
						&& for os in darwin linux windows; do \\
							[ "$os" = 'windows' ] && ext='.exe' || ext=''; \\
							\\
							GOOS="$os" GOARCH="$arch" \\
								go build \\
									-v \\
									-ldflags '-s -w' \\
									-a \\
									-tags netgo \\
									-installsuffix netgo \\
									-o "bin/bashbrew-$os-$arch$ext" \\
									./src/bashbrew; \\
							\\
					# TODO embed the manifest-tool version somewhere in the bashbrew repo/source itself?
							wget -O "bin/manifest-tool-$os-$arch$ext" "https://github.com/estesp/manifest-tool/releases/download/v0.4.0/manifest-tool-$os-$arch$ext"; \\
							\\
						done \\
						&& ls -l bin
				EODF
				rm -rf bin
				docker run -i --rm bashbrew:cross tar -c bin \\
					| tar -xv
			'''
		}
	}

	stage('Archive') {
		archiveArtifacts(
			artifacts: 'bin/*',
			fingerprint: true,
			onlyIfSuccessful: true,
		)
	}
}
