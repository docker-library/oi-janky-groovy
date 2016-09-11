def a = 'foo'

def test = { str ->
	{ ->
		str
	}
}(a)

this
