stackRegion := "eu-west-1"

stackParams in Staging := Map("NumberWithRange" -> "2", "StringWithLength" -> "longstring")

stackTags in Staging := Map("env" -> "staging", "app" -> "sample")
