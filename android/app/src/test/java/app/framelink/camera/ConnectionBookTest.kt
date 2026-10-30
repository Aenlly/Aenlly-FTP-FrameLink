package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class ConnectionBookTest{
    private class MemoryStore:SecureValueStore{
        val values=mutableMapOf<String,String>()
        override fun get(name:String)=values[name].orEmpty()
        override fun put(name:String,value:String){values[name]=value}
        override fun remove(name:String){values.remove(name)}
        override fun contains(name:String)=name in values
        override fun names()=values.keys.toSet()
    }
    private fun config(host:String="camera-a",fingerprint:String="fingerprint-a")=ConnectionConfig(host=host,user="private-user-$host",password="private-password-$host",fingerprint=fingerprint)

    @Test fun unsavedCredentialsNeverEnterTheStoredSnapshot(){
        val store=MemoryStore();val input=config();ConnectionBook(store).completed(input,input.fingerprint,false)
        val restarted=ConnectionBook(store)
        assertTrue(restarted.entries().isEmpty());assertEquals("",restarted.last().user);assertEquals("",restarted.last().password)
        assertEquals(input.host,restarted.last().host);assertTrue(restarted.requiresCredentials())
        assertFalse(store.values.values.any{input.user in it||input.password in it})
    }
    @Test fun savedDevicesAndProtocolsStaySeparateWhenUpdatingAndForgetting(){
        val store=MemoryStore();val book=ConnectionBook(store);val a=config();val b=config(fingerprint="fingerprint-b").copy(user="user-b",password="password-b")
        val canon=config().copy(protocol=CameraProtocols.CANON,port=8080)
        book.completed(a,a.fingerprint,true);book.completed(b,b.fingerprint,true);book.completed(canon,"canon-id",true)
        assertEquals(3,ConnectionBook(store).entries().size)
        val aId=book.entries().first{it.identity==a.fingerprint}.id
        book.forget(aId)
        assertEquals(setOf("fingerprint-b","canon-id"),book.entries().map{it.identity}.toSet())
        book.completed(b.copy(password="new-password-b"),b.fingerprint,true)
        assertEquals(2,book.entries().size);assertEquals("new-password-b",book.last().password)
        book.completed(b.copy(password="new-password-b"),b.fingerprint,false)
        assertEquals(listOf("canon-id"),book.entries().map{it.identity})
    }
    @Test fun legacyCredentialMigrationUpgradesTheUnboundRecordWithoutDuplicates(){
        val store=MemoryStore();val old=config(fingerprint="")
        store.put("camera.config",Json.encode(old));val book=ConnectionBook(store)
        assertEquals(old.password,book.last().password);assertFalse(store.contains("camera.config"))
        book.completed(old.copy(fingerprint="confirmed"),"confirmed",true)
        assertEquals(1,book.entries().size);assertEquals("confirmed",book.entries().single().identity)
    }
    @Test fun corruptedNewBookDoesNotEraseLegacyCredentials(){
        val store=MemoryStore();store.put("camera.config",Json.encode(config()));store.put("camera.connections.v1","broken")
        assertTrue(runCatching{ConnectionBook(store).entries()}.isFailure)
        assertTrue(store.contains("camera.config"));assertEquals("broken",store.get("camera.connections.v1"))
    }
}
