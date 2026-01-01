package xyz.wallpanel.pro.network

interface IMqttManagerListener {
    fun subscriptionMessage(id: String, topic: String, payload: String)
    fun handleMqttException(errorMessage: String)
    fun handleMqttDisconnected()
    fun handleMqttConnected()
}