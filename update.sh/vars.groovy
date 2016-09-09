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
def repos = []

// map of repo metadata: ["celery": ["url": "...", ...], ...]
def reposMeta = [:]

for (int i = 0; i < rawReposData.size(); ++i) {
	repo = rawReposData[0]
	repoMeta = rawReposData[1]

	// apply "defaultRepoMeta" for missing bits
	//   wouldn't it be grand if we could just use "map1 + map2" here??
	//   dat Jenkins sandbox...
	for (int j = 0; j < defaultRepoMeta.size(); ++i) {
		key = defaultRepoMeta[j][0]
		val = defaultRepoMeta[j][1]
		if (repoMeta[key] == null) {
			repoMeta[key] = val
		}
	}

	repoMeta['url'].replaceAll('%%REPO%%', repo)

	repos << repo
	reposMeta[repo] = repoMeta
}

// return "this", just in case ("load" in Jenkins pipeline, for example)
this
