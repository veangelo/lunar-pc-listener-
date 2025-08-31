package com.gaurav.avnc

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.*

class PCDiscovery(private val context: Context) {
    
    fun discoverPC(listener: DiscoveryListener) {
        Thread {
            try {
                // Send broadcast to find PC
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val socket = DatagramSocket()
                socket.broadcast = true
                
                // The magic message that PC is listening for
                val discoverMessage = "INMO_AAR3_DISCOVER"
                val sendData = discoverMessage.toByteArray()
                val sendPacket = DatagramPacket(
                    sendData, 
                    sendData.size, 
                    broadcastAddress, 
                    9999
                )
                
                socket.send(sendPacket)
                Log.d("PCDiscovery", "Discovery packet sent")
                
                // Listen for PC response
                val receiveData = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)
                
                // Wait 5 seconds for response
                socket.soTimeout = 5000
                socket.receive(receivePacket)
                
                val response = String(receivePacket.data, 0, receivePacket.length)
                if (response == "INMO_AAR3_RESPONSE") {
                    val pcAddress = receivePacket.address
                    Log.d("PCDiscovery", "Found PC at: ${pcAddress.hostAddress}")
                    
                    // Tell app we found the PC!
                    listener.onPCDiscovered(pcAddress.hostAddress)
                }
                
                socket.close()
                
            } catch (e: SocketTimeoutException) {
                Log.d("PCDiscovery", "No PC found - timeout")
                listener.onDiscoveryFailed("No PC found on network")
            } catch (e: IOException) {
                Log.e("PCDiscovery", "Discovery error: ${e.message}")
                listener.onDiscoveryFailed("Network error: ${e.message}")
            }
        }.start()
    }
    
    // These are the messages our finder can send
    interface DiscoveryListener {
        fun onPCDiscovered(pcAddress: String)
        fun onDiscoveryFailed(error: String)
    }
}