// https://github.com/jenkinsci/pipeline-examples/blob/666e5e3f8104efd090e698aa9b5bc09dd6bf5997/docs/BEST_PRACTICES.md#groovy-gotchas
// tl;dr, iterating over Maps in pipeline groovy is pretty broken in real-world use
def defaultRepoMeta = [
	['url', 'git@github.com:docker-library/%%REPO%%.git'],
	['env', '.+_VERSION'], // awk regex, anchored
	['otherEnvs', []],
]
def rawReposData = [
	['cassandra', [
		'env': 'CASSANDRA_VERSION',
	]],
	['celery', [
		'env': 'CELERY_VERSION',
	]],
	['docker', [
		'env': 'DOCKER_VERSION',
	]],
	['golang', [
		'env': 'GOLANG_VERSION',
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
	['php', [
		'env': 'PHP_VERSION',
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
	['ruby', [
		'env': 'RUBY_VERSION',
		'otherEnvs': [
			['rubygems', 'RUBYGEMS_VERSION'],
			['bundler', 'BUNDLER_VERSION'],
		],
		
	]],
	['wordpress', [
		'env': 'WORDPRESS_VERSION',
	]],
]

// list of repos: ["celery", "wordpress", ...]
this.repos = []

// map of repo metadata: ["celery": ["url": "...", ...], ...]
this.reposMeta = [:]
def repoMeta(repo) {
	return this.reposMeta[repo]
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

	this.repos << repo
	this.reposMeta[repo] = repoMeta
}

// return "this" (for use via "load" in Jenkins pipeline, for example)
this
