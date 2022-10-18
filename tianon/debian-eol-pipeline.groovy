properties([
	disableConcurrentBuilds(),
	parameters([
		string(
			name: 'targetSuites',
			description: 'Space-separated list of suites to generate, ala "sarge etch lenny".',
		),
	]),
])

def vars = fileLoader.fromGit(
	'tianon/debuerreotype/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)

env.debuerreotypeVersion = vars.debuerreotypeVersion
env.debuerreotypeExamplesCommit = vars.debuerreotypeExamplesCommit
env.TZ = 'UTC'

node() {
	env.DEBUERREOTYPE_DIRECTORY = workspace + '/debuerreotype'

	dir(env.DEBUERREOTYPE_DIRECTORY) {
		deleteDir()
		stage('Download debuerreotype') {
			sh '''
				wget -O 'debuerreotype.tgz' "https://github.com/debuerreotype/debuerreotype/archive/${debuerreotypeVersion}.tar.gz"
				tar -xf debuerreotype.tgz --strip-components=1
				if [ "$debuerreotypeExamplesCommit" != "$debuerreotypeVersion" ]; then
					wget -O 'debuerreotype-examples.tgz' "https://github.com/debuerreotype/debuerreotype/archive/${debuerreotypeExamplesCommit}.tar.gz"
					rm -rf examples
					tar -xf debuerreotype-examples.tgz --strip-components=1 \
						"debuerreotype-${debuerreotypeExamplesCommit}/.docker-image.sh" \
						"debuerreotype-${debuerreotypeExamplesCommit}/.dockerignore" \
						"debuerreotype-${debuerreotypeExamplesCommit}/Dockerfile" \
						"debuerreotype-${debuerreotypeExamplesCommit}/docker-run.sh" \
						"debuerreotype-${debuerreotypeExamplesCommit}/examples"
				fi
				rm -f debuerreotype*.tgz
				./scripts/debuerreotype-version
			'''
		}
	}

	dir('eol') {
		stage('Checkout') {
			checkout([
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'git@github.com:debuerreotype/docker-debian-eol-artifacts.git',
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
					[$class: 'CleanCheckout'],
				],
			])
			sh '''
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'
			'''
		}

		targetSuites = targetSuites.trim().tokenize()
		for (targetSuite in targetSuites) {
			withEnv(['targetSuite=' + targetSuite]) {
				stage('Generate ' + targetSuite) {
					sh './generate.sh "$targetSuite"'
				}

				stage('Commit ' + targetSuite) {
					sh '''
						ts="$(awk -v suite="$targetSuite" '$1 == suite { print $2; exit }' suites)"
						dt="$(date --date "$ts" --rfc-2822)"
						serial="$(date --date "$ts" '+%Y%m%d')"
						arches="$(xargs < "$targetSuite/arches")"
						export GIT_AUTHOR_DATE="$dt" GIT_COMMITTER_DATE="$dt"
						git reset --mixed origin/master
						git add -A "$targetSuite"
						git commit -m "Generate $targetSuite ($serial) [$arches]"
					'''
				}

				sshagent(['docker-library-bot']) {
					stage('Push ' + targetSuite) {
						sh 'git push -f origin "HEAD:refs/heads/dist-$targetSuite"'
					}
				}
			}
		}
	}
}
