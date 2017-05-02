env.ACT_ON_IMAGE = env.JOB_BASE_NAME // "memcached", etc
env.ACT_ON_NAMESPACE = env.JOB_NAME.split('/')[-2] // "i386", etc
env.TARGET_REPO = env.ACT_ON_NAMESPACE + '/' + env.ACT_ON_IMAGE

node('multiarch-' + env.ACT_ON_NAMESPACE) {
	def workspace = sh(returnStdout: true, script: 'pwd').trim()

	env.BASHBREW_LIBRARY = workspace + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: false,
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

	env.BASHBREW_ARCHIVE_TEMPLATE = '''
{{- range $.Entries -}}
	{{- $from := $.DockerFrom . -}}
	{{- $dir := join "/" $.RepoName (.Tags | first) -}}
	git -C "${BASHBREW_CACHE:-$HOME/.cache/bashbrew}/git" archive --format=tar
	{{- " " -}}
	{{- "--prefix=" -}}'{{- $dir -}}{{- "/" -}}'
	{{- " " -}}
	{{- .GitCommit -}}
	{{- ":" -}}
	{{- (eq .Directory ".") | ternary "" .Directory -}}
	{{- " | tar -x" -}}
	{{- "\\n" -}}
{{- end -}}
'''

	env.BASHBREW_BUILD_TEMPLATE = '''
{{- range $.SortedEntries -}}
	{{- $firstTag := .Tags | first -}}
	{{- $dir := join "/" $.RepoName $firstTag -}}
	from="$(awk '$1 == "FROM" { print $2; exit }' '{{- $dir -}}/Dockerfile')"
	{{- "\\n" -}}
	if ! docker inspect --type image "$from" > /dev/null 2>&1; then
	{{- "\\n" -}}
		echo >&2 "warning: '$from' does not exist; skipping '$TARGET_REPO:{{- $firstTag -}}'"
		{{- "\\n" -}}
	else
	{{- "\\n" -}}
		docker build -t "$TARGET_REPO":'{{- $firstTag -}}' '{{- $dir -}}'
		{{- "\\n" -}}
		{{- range .Tags -}}
			{{- if ne . $firstTag -}}
				docker tag "$TARGET_REPO":'{{- $firstTag -}}' "$TARGET_REPO":'{{- . -}}'
				{{- "\\n" -}}
			{{- end -}}
		{{- end -}}
	fi
	{{- "\\n" -}}
{{- end -}}
'''

	env.BASHBREW_PUSH_TEMPLATE = '''
{{- range $.SortedEntries -}}
	{{- range .Tags -}}
		img="$TARGET_REPO":'{{- . -}}'
		{{- "\\n" -}}
		if docker inspect --type image "$img" > /dev/null 2>&1; then
		{{- "\\n" -}}
			docker push "$img"
			{{- "\\n" -}}
		fi
		{{- "\\n" -}}
	{{- end -}}
{{- end -}}
'''

	ansiColor('xterm') { dir('src') {
		deleteDir()

		stage('Fetch') {
			sh '''
				script="$(bashbrew cat -f "$BASHBREW_ARCHIVE_TEMPLATE" "$ACT_ON_IMAGE")"
				eval "$script"
			'''
		}

		stage('Munge') {
			sh '''
				sed -ri \\
					-e "s!^FROM !FROM $ACT_ON_NAMESPACE/!" \\
					*/*/Dockerfile
			'''
		}

		stage('Pull') {
			sh '''
				for df in */*/Dockerfile; do
					from="$(awk '$1 == "FROM" { print $2; exit }' "$df")"
					echo "$from"
				done | sort -u | xargs -n1 docker pull || true
			'''
		}

		stage('Build') {
			sh '''
				script="$(bashbrew cat -f "$BASHBREW_BUILD_TEMPLATE" "$ACT_ON_IMAGE")"
				eval "$script"
			'''
		}

		stage('Push') {
			sh '''
				script="$(bashbrew cat -f "$BASHBREW_PUSH_TEMPLATE" "$ACT_ON_IMAGE")"
				eval "$script"
			'''
		}
	} }
}
