package xyz.wallpanel.pro.ext


fun String.convertStringToArray(str: String): Array<String> {
    val strSeparator = ","
    return str.split(strSeparator).toTypedArray()
}
