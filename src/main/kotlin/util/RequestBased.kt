package util

import java.text.MessageFormat

abstract class RequestBased {
    //以下是对请求api的wrapper，通过限定参数来提高准确性

    protected fun String.requiredOneParam() = { param1: String ->
        MessageFormat.format(this, param1)
    }

    protected fun String.requiredTwoParam() = { param1: String, param2: String ->
        MessageFormat.format(this, param1, param2)
    }

    protected fun String.requiredThreeParam() = { param1: String, param2: String, param3: String ->
        MessageFormat.format(this, param1, param2, param3)
    }

    protected fun String.requiredFourParam() = { param1: String, param2: String, param3: String, param4: String ->
        MessageFormat.format(this, param1, param2, param3, param4)
    }

    protected fun String.requiredFiveParam() =
        { param1: String, param2: String, param3: String, param4: String, param5: String ->
            MessageFormat.format(this, param1, param2, param3, param4, param5)
        }
}