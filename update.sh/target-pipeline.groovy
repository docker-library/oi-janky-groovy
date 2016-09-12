def vars = fileLoader.fromGit(
	'update.sh/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'', // node/label
)
def repoMeta = vars.repoMeta(env.JOB_BASE_NAME)

// TODO actually invoke "update.sh", etc
node {
	stage('Test') {
		echo(repoMeta['url'])
	}
}
