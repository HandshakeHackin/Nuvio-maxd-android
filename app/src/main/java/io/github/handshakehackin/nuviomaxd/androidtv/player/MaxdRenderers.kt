package io.github.handshakehackin.nuviomaxd.androidtv.player

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.text.TextOutput
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object PcmMix {
    fun sample(value:Float,gain:Float,night:Int):Short{
        val amplified=value*gain
        val compressed=if(night==0)amplified else amplified/(1f+abs(amplified)*(if(night==1)0.5f else 1.5f))
        return (compressed*32768f).toInt().coerceIn(-32768,32767).toShort()
    }
    fun stereo(frame:FloatArray,lfe:Float):Pair<Float,Float>{
        val output=FloatArray(2);stereoInto(frame,lfe,output);return output[0] to output[1]
    }
    fun stereoInto(frame:FloatArray,lfe:Float,output:FloatArray){
        if(frame.size==1){output[0]=frame[0];output[1]=frame[0];return}
        var left=frame[0];var right=frame[1]
        when(frame.size){
            3->{left+=frame[2]*0.707f;right+=frame[2]*0.707f}
            4->{left+=frame[2]*0.707f;right+=frame[3]*0.707f}
            5->{left+=frame[2]*0.707f+frame[3]*0.5f;right+=frame[2]*0.707f+frame[4]*0.5f}
            else->if(frame.size>=6){left+=frame[2]*0.707f+frame[3]*lfe+frame[4]*0.5f;right+=frame[2]*0.707f+frame[3]*lfe+frame[5]*0.5f
                if(frame.size>=8){left+=frame[6]*0.5f;right+=frame[7]*0.5f}}
        };output[0]=left;output[1]=right
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MaxdRenderers(context:Context,private val settings:JSONObject):DefaultRenderersFactory(context){
    init{setEnableDecoderFallback(true);setEnableAudioFloatOutput(false)}
    override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink{
        val builder=DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        val processing=settings.optInt("gain",100)!=100||settings.optInt("nightMode")!=0||settings.optInt("audioDelay")!=0||!settings.optBoolean("surround",true)
        if(processing||settings.optString("audioMode","auto")=="pcm")builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        builder.setAudioProcessors(arrayOf(PcmProcessor(settings)))
        return builder.build()
    }
    override fun buildTextRenderers(context:Context,output:TextOutput,looper:Looper,mode:Int,renderers:ArrayList<Renderer>){
        val delegates=ArrayList<Renderer>();super.buildTextRenderers(context,output,looper,mode,delegates)
        val delay=settings.optLong("subtitleDelay")*1000
        delegates.forEach{delegate->renderers.add(if(delay==0L)delegate else object:Renderer by delegate{
            override fun render(positionUs:Long,elapsedRealtimeUs:Long){delegate.render((positionUs-delay).coerceAtLeast(0),elapsedRealtimeUs)}
        })}
    }
    override fun getCodecAdapterFactory():MediaCodecAdapter.Factory{
        val delegate=super.getCodecAdapterFactory()
        return MediaCodecAdapter.Factory{configuration->
            if(Build.VERSION.SDK_INT>=31&&settings.optString("hdr")=="sdr"&&configuration.mediaFormat.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true){
                configuration.mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            delegate.createAdapter(configuration)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class PcmProcessor(private val settings:JSONObject):BaseAudioProcessor(){
    private var padding=0
    private var skipping=0
    private var channels=2
    private var frame=FloatArray(2)
    private val mixed=FloatArray(2)
    override fun onConfigure(input:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat{
        if(input.encoding!=C.ENCODING_PCM_16BIT)return AudioProcessor.AudioFormat.NOT_SET
        if(settings.optInt("gain",100)==100&&settings.optInt("nightMode")==0&&settings.optInt("audioDelay")==0&&settings.optBoolean("surround",true))return AudioProcessor.AudioFormat.NOT_SET
        channels=if(settings.optBoolean("surround",true))input.channelCount else 2
        frame=FloatArray(input.channelCount)
        return AudioProcessor.AudioFormat(input.sampleRate,channels,C.ENCODING_PCM_16BIT)
    }
    override fun onFlush(){
        val delay=settings.optInt("audioDelay").coerceIn(-2000,2000)
        padding=if(delay>0)(inputAudioFormat.sampleRate.toLong()*channels*2*delay/1000).toInt()else 0
        skipping=if(delay<0)(inputAudioFormat.sampleRate.toLong()*inputAudioFormat.bytesPerFrame*(-delay)/1000).toInt()else 0
    }
    override fun queueInput(input:ByteBuffer){
        input.order(ByteOrder.LITTLE_ENDIAN)
        val skip=minOf(skipping,input.remaining());input.position(input.position()+skip);skipping-=skip
        val frames=input.remaining()/inputAudioFormat.bytesPerFrame
        val output=replaceOutputBuffer(padding+frames*channels*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(padding){output.put(0)};padding=0
        val gain=settings.optInt("gain",100).coerceIn(50,300)/100f;val night=settings.optInt("nightMode");val lfe=settings.optInt("lfeMix",50).coerceIn(0,100)/100f
        repeat(frames){
            for(channel in frame.indices)frame[channel]=input.short/32768f
            if(channels==2&&frame.size!=2){PcmMix.stereoInto(frame,lfe,mixed);output.putShort(PcmMix.sample(mixed[0],gain,night));output.putShort(PcmMix.sample(mixed[1],gain,night))}
            else for(channel in 0 until channels)output.putShort(PcmMix.sample(frame[channel],gain,night))
        }
        output.flip()
    }
}
