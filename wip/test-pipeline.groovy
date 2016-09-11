node {
	git 'https://github.com/docker-library/oi-janky-groovy.git'
	def a = load('wip/test-load.groovy')
	echo(a.@a)
}
