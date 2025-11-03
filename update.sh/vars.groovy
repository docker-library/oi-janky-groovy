// https://github.com/jenkinsci/pipeline-examples/blob/666e5e3f8104efd090e698aa9b5bc09dd6bf5997/docs/BEST_PRACTICES.md#groovy-gotchas
// tl;dr, iterating over Maps in pipeline groovy is pretty broken in real-world use
def defaultRepoMeta = [
	['url', 'git@github.com:docker-library/%%REPO%%.git'],
	['oi-fork', 'git@github.com:docker-library-bot/official-images.git'],
	['pipeline-script', 'update.sh/versions-pipeline.groovy'],
	['env', '.+_VERSION'], // gawk regex, anchored
	['otherEnvs', []],
	['branch-base', 'master'], // branch to check out from
	['branch-push', 'master'], // branch to push to
	['update-script', './update.sh'],
	['disabled', false],
	['bot-branch', true],
]
def rawReposData = [
	['buildpack-deps', [:]],
	['busybox', [
		'pipeline-script': 'update.sh/legacy-pipeline.groovy',
		'env': 'BUSYBOX_VERSION',
		'update-script': 'true', // TODO determine if more can/should be done here
	]],
	['cassandra', [:]],
	['docker', [:]],
	['drupal', [:]],
	['gcc', [:]],
	['ghost', [:]],
	['golang', [:]],
	['haproxy', [:]],
	['hello-world', [
		'pipeline-script': 'update.sh/legacy-pipeline.groovy',
		'update-script': 'true',
	]],
	['httpd', [
		'pipeline-script': 'update.sh/legacy-pipeline.groovy',
		'env': 'HTTPD_VERSION',
		'otherEnvs': [
			['nghttp2', 'NGHTTP2_VERSION'],
			['openssl', 'OPENSSL_VERSION'],
		],
	]],
	['julia', [:]],
	['memcached', [:]],
	['mongo', [:]],
	['mysql', [:]],
	['openjdk', [:]],
	['php', [:]],
	['postgres', [:]],
	['pypy', [:]],
	['python', [:]],
	['rabbitmq', [:]],
	['redmine', [:]],
	['ruby', [:]],
	['tomcat', [:]],
	['wordpress', [:]],

	// TODO it would be great to have one of these jobs per namespace ("mcr.microsoft.com/windows", "redhat", etc.)
	['external-pins', [
		'pipeline-script': 'update.sh/external-pins-pipeline.groovy',
	]],

	// tianon
	['bash', [
		'url': 'git@github.com:tianon/docker-bash.git',
	]],
	['cirros', [
		'pipeline-script': 'update.sh/legacy-pipeline.groovy',
		'url': 'git@github.com:tianon/docker-brew-cirros.git',
		'update-script': 'true',
	]],
	['irssi', [
		'pipeline-script': 'update.sh/legacy-pipeline.groovy',
		'url': 'git@github.com:jessfraz/irssi.git',
		'env': 'IRSSI_VERSION',
	]],

	// pierreozoux
	['matomo', [
		'pipeline-script': 'update.sh/legacy-pipeline.groovy',
		'url': 'git@github.com:matomo-org/docker.git',
		'env': 'MATOMO_VERSION',
		'bot-branch': false,
	]],

	// paultag
	['hylang', [
		'url': 'git@github.com:hylang/docker-hylang.git',
	]],
]

// list of repos: ["celery", "wordpress", ...]
repos = []

// map of repo metadata: ["celery": ["url": "...", ...], ...]
reposMeta = [:]
def repoMeta(repo) {
	return reposMeta[repo]
}

for (int i = 0; i < rawReposData.size(); ++i) {
	def repo = rawReposData[i][0]
	def repoMeta = rawReposData[i][1]

	// apply "defaultRepoMeta" for missing bits
	//   wouldn't it be grand if we could just use "map1 + map2" here??
	//   dat Jenkins sandbox...
	for (int j = 0; j < defaultRepoMeta.size(); ++j) {
		def key = defaultRepoMeta[j][0]
		def val = defaultRepoMeta[j][1]
		if (repoMeta[key] == null) {
			repoMeta[key] = val
		}
	}

	repoMeta['url'] = repoMeta['url'].replaceAll('%%REPO%%', repo)

	repos << repo
	reposMeta[repo] = repoMeta
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
