// https://github.com/jenkinsci/pipeline-examples/blob/666e5e3f8104efd090e698aa9b5bc09dd6bf5997/docs/BEST_PRACTICES.md#groovy-gotchas
// tl;dr, iterating over Maps in pipeline groovy is pretty broken in real-world use
def defaultRepoMeta = [
	['url', 'git@github.com:docker-library/%%REPO%%.git'],
	['env', '.+_VERSION'], // awk regex, anchored
	['otherEnvs', []],
]
def rawReposData = [
	// TODO busybox? (BUSYBOX_VERSION)
	['cassandra', [
		'env': 'CASSANDRA_VERSION',
	]],
	['celery', [
		'env': 'CELERY_VERSION',
	]],
	['django', [
		'env': 'DJANGO_VERSION',
	]],
	['docker', [
		'env': 'DOCKER_VERSION',
	]],
	['drupal', [
		'env': 'DRUPAL_VERSION',
	]],
	// TODO elasticsearch (ELASTICSEARCH_VERSION)
	// TODO gcc (GCC_VERSION)
	['ghost', [
		'env': 'GHOST_VERSION',
	]],
	['golang', [
		'env': 'GOLANG_VERSION',
	]],
	['haproxy', [
		'env': 'HAPROXY_VERSION',
	]],
	['httpd', [
		'env': 'HTTPD_VERSION',
	]],
	['julia', [
		'env': 'JULIA_VERSION',
	]],
	// TODO kibana (KIBANA_VERSION)
	// TODO logstash (LOGSTASH_VERSION)
	['mariadb', [
		'env': 'MARIADB_VERSION',
	]],
	['memcached', [
		'env': 'MEMCACHED_VERSION',
	]],
	['mongo', [
		'env': 'MONGO_VERSION',
	]],
	['mysql', [
		'env': 'MYSQL_VERSION',
	]],
	['openjdk', [
		'env': 'JAVA_VERSION',
		'otherEnvs': [
			['alpine', 'JAVA_ALPINE_VERSION'],
			['debian', 'JAVA_DEBIAN_VERSION'],
		],
	]],
	['percona', [
		'env': 'PERCONA_VERSION',
	]],
	['php', [
		'env': 'PHP_VERSION',
	]],
	['postgres', [
		'env': 'PG_VERSION',
	]],
	['pypy', [
		'env': 'PYPY_VERSION',
		'otherEnvs': [
			['pip', 'PYTHON_PIP_VERSION'],
		],
	]],
	['python', [
		'env': 'PYTHON_VERSION',
		'otherEnvs': [
			['pip', 'PYTHON_PIP_VERSION'],
		],
	]],
	['rabbitmq', [
		'env': 'RABBITMQ_VERSION',
		'otherEnvs': [
			['debian', 'RABBITMQ_DEBIAN_VERSION'],
		],
	]],
	['rails', [
		'env': 'RAILS_VERSION',
	]],
	['redis', [
		'env': 'REDIS_VERSION',
	]],
	['redmine', [
		'env': 'REDMINE_VERSION',
		'otherEnvs': [
			['passenger', 'PASSENGER_VERSION'],
		],
	]],
	['ruby', [
		'env': 'RUBY_VERSION',
		'otherEnvs': [
			['rubygems', 'RUBYGEMS_VERSION'],
			['bundler', 'BUNDLER_VERSION'],
		],
	]],
	['tomcat', [
		'env': 'TOMCAT_VERSION',
	]],
	['wordpress', [
		'env': 'WORDPRESS_VERSION',
	]],

	// tianon
	['irssi', [
		'url': 'git@github.com:jfrazelle/irssi.git',
		'env': 'IRSSI_VERSION',
	]],

	// pierreozoux
	// TODO piwik (PIWIK_VERSION)
	// TODO rocket.chat (RC_VERSION)
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
