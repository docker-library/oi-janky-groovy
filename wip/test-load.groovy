def test() { } // to be re-defined later

def a = 'foo'

this.test = { str ->
	{ ->
		str
	}
}(a)

this.b = 'bar'

this
