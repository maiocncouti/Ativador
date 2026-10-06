package com.j.g.nexus_ativador

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.j.g.nexus_ativador.ui.theme.Nexus_AtivadorTheme
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

// CORES DO SEU PAINEL ULTRA PREMIUM
val DeepSpace = Color(0xFF030508)
val SurfaceColor = Color(0xFF0A0E17)
val AccentCyan = Color(0xFF06B6D4)
val AccentBlue = Color(0xFF3B82F6)

@Serializable
data class PacoteMestre(
    val arquivos: List<String> = emptyList(),
    val aplicativos: List<AppVersao> = emptyList()
)

@Serializable
data class AppVersao(
    val versao: String,
    val url_download: String
)

@Serializable
data class RespostaAtivacao(
    val sucesso: Boolean,
    val mensagem: String,
    val revendedor_id: Int? = null,
    val url_json: String? = null
)

class MainActivity : ComponentActivity() {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            var pacoteMestre by remember { mutableStateOf<PacoteMestre?>(null) }
            var logMessage by remember { mutableStateOf("Conectando ao painel...") }
            var isAtivado by remember { mutableStateOf(false) }

            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("NexusPrefs", Context.MODE_PRIVATE) }

            // Lógica de Inicialização: Verifica se já existe uma URL de painel ativa
            LaunchedEffect(Unit) {
                val urlAtiva = prefs.getString("url_ativa", null)
                if (urlAtiva != null) {
                    isAtivado = true
                    logMessage = "Sincronizando dados..."
                    val dados = buscarDadosDinamicos(urlAtiva)
                    if (dados != null) {
                        pacoteMestre = dados
                        logMessage = if (dados.aplicativos.isEmpty()) "ERRO: LISTA DE APPS VAZIA" else "Sincronizado!"
                    } else {
                        logMessage = "ERRO: PAINEL OFF"
                    }
                }
            }

            Nexus_AtivadorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = DeepSpace) {
                    if (!isAtivado) {
                        FluxoAtivacaoApp(
                            client = client,
                            onAtivadoComSucesso = { urlRecebida ->
                                prefs.edit().putString("url_ativa", urlRecebida).apply()
                                isAtivado = true
                                lifecycleScope.launch {
                                    val dados = buscarDadosDinamicos(urlRecebida)
                                    pacoteMestre = dados
                                    logMessage = if (dados?.aplicativos?.isEmpty() == true) "ERRO: SEM APPS" else "Sucesso!"
                                }
                            }
                        )
                    } else {
                        AtivadorUI(
                            pacoteMestre = pacoteMestre,
                            log = logMessage,
                            onInstalar = { app, manualText ->
                                lifecycleScope.launch {
                                    val idDaConfig = pacoteMestre?.arquivos?.randomOrNull()
                                    iniciarProcessoDownload(app, idDaConfig, manualText) { msg ->
                                        logMessage = msg
                                    }
                                }
                            },
                            onReset = {
                                prefs.edit().remove("url_ativa").apply()
                                isAtivado = false
                                pacoteMestre = null
                                logMessage = "Conectando ao painel..."
                            },
                            onLimparConfigs = {
                                lifecycleScope.launch {
                                    limparConfigsAntigas { logMessage = it }
                                }
                            },
                            onDarPermissao = {
                                pedirPermissoesAcessoTotal()
                            },
                            onInjetarConfig = {
                                val configId = pacoteMestre?.arquivos?.randomOrNull()
                                if (configId != null) {
                                    lifecycleScope.launch {
                                        baixarEInjetarConfig(configId) { logMessage = it }
                                    }
                                } else {
                                    Toast.makeText(this, "Nenhuma config disponível", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onInjetarManual = { texto ->
                                lifecycleScope.launch {
                                    injetarTextoDireto(texto) { logMessage = it }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun buscarDadosDinamicos(url: String): PacoteMestre? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(url)
            response.body<PacoteMestre>()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun iniciarProcessoDownload(app: AppVersao, configId: String?, manualText: String, onStatus: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            if (manualText.isNotBlank()) {
                onStatus("Prioridade: Injeção Manual...")
                injetarTextoDireto(manualText, onStatus)
            } else if (configId != null) {
                onStatus("Baixando Configuração...")
                baixarEInjetarConfig(configId, onStatus)
            }
            onStatus("Baixando UniTV ${app.versao}...")
            baixarEInstalarAPK(app.url_download, "UniTV_${app.versao}.apk")
        }
    }

    private suspend fun baixarEInjetarConfig(configId: String, onStatus: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val url = "http://192.xx.xx.:3000/api/configs/download/$configId"
                //seu  ip da sua vps ou domino

                val response: HttpResponse = client.get(url)
                val bytes = response.body<ByteArray>()
                
                salvarBytesComoConfig(bytes)
                
                withContext(Dispatchers.Main) {
                    onStatus("Configuração Injetada!")
                    Toast.makeText(this@MainActivity, "Configuração Injetada com Sucesso!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onStatus("Erro ao injetar config.")
                }
            }
        }
    }

    private suspend fun injetarTextoDireto(texto: String, onStatus: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (texto.isBlank()) return@withContext
                salvarBytesComoConfig(texto.toByteArray())
                withContext(Dispatchers.Main) {
                    onStatus("MANUAL: TEXTO INJETADO!")
                    Toast.makeText(this@MainActivity, "Configuração Manual Injetada!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("Erro na injeção manual") }
            }
        }
    }

    private fun salvarBytesComoConfig(bytes: ByteArray) {
        val paths = listOf(
            Environment.getExternalStorageDirectory(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        
        paths.forEach { path ->
            val file = File(path, "unitv_free.config")
            FileOutputStream(file).use { it.write(bytes) }
        }
    }

    private suspend fun limparConfigsAntigas(onStatus: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val filesToDelete = listOf("unitv_free.config", "unitv.config", "unitv_free.config.tmp")
            val paths = listOf(
                Environment.getExternalStorageDirectory(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )
            
            var count = 0
            paths.forEach { path ->
                filesToDelete.forEach { fileName ->
                    val file = File(path, fileName)
                    if (file.exists() && file.delete()) count++
                }
            }
            
            withContext(Dispatchers.Main) {
                onStatus("Limpeza concluída ($count arquivos).")
                Toast.makeText(this@MainActivity, "Limpeza Concluída!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pedirPermissoesAcessoTotal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "Permissão de Arquivos já concedida!", Toast.LENGTH_SHORT).show()
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                100
            )
        }
    }

    private fun baixarEInstalarAPK(url: String, fileName: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Nexus Downloader")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    instalarAPK(fileName)
                    unregisterReceiver(this)
                }
            }
        }
        
        ContextCompat.registerReceiver(
            this, onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun instalarAPK(fileName: String) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) {
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        }
    }
}

@Composable
fun FluxoAtivacaoApp(client: HttpClient, onAtivadoComSucesso: (String) -> Unit) {
    var keyInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var mensagemErro by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(70.dp).background(Brush.linearGradient(listOf(AccentCyan, AccentBlue)), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = DeepSpace, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("NEXUS ATIVADOR", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Insira sua chave para liberar o acesso", fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Chave de Ativação") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = Color.DarkGray,
                focusedLabelColor = AccentCyan,
                unfocusedTextColor = Color.White,
                focusedTextColor = Color.White
            )
        )

        if (mensagemErro != null) {
            Text(text = mensagemErro!!, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (keyInput.isBlank()) return@Button
                isLoading = true
                mensagemErro = null
                scope.launch {
                    try {           //seu  ip da sua vps ou domino
                        val response: HttpResponse = client.post("http://192.168.xx.xx:3000/api/ativar") {
                            contentType(ContentType.Application.Json)
                            setBody(mapOf("codigo" to keyInput))
                        }
                        val resultado = response.body<RespostaAtivacao>()
                        if (resultado.sucesso) {
                            onAtivadoComSucesso(resultado.url_json ?: "")
                        } else {
                            mensagemErro = resultado.mensagem
                        }
                    } catch (e: Exception) {
                        mensagemErro = "Erro de conexão com o servidor."
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = DeepSpace, modifier = Modifier.size(24.dp))
            } else {
                Text("ATIVAR SISTEMA", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DeepSpace)
            }
        }
    }
}

@Composable
fun AtivadorUI(
    pacoteMestre: PacoteMestre?, 
    log: String, 
    onInstalar: (AppVersao, String) -> Unit, 
    onReset: () -> Unit,
    onLimparConfigs: () -> Unit,
    onDarPermissao: () -> Unit,
    onInjetarConfig: () -> Unit,
    onInjetarManual: (String) -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(AccentCyan, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text("NEXUS PANEL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            TextButton(onClick = onReset) {
                Text("LIMPAR", color = Color.Red.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
        
        Text(log, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        
        Spacer(Modifier.height(12.dp))

        // BOTÃO OPÇÕES AVANÇADAS
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(
                if (showAdvanced) "ESCONDER AVANÇADAS" else "OPÇÕES AVANÇADAS",
                color = AccentCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (showAdvanced) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onLimparConfigs,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("LIMPAR CONFIGS ANTIGAS", fontSize = 12.sp)
                }

                Button(
                    onClick = onDarPermissao,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("DAR PERMISSÃO (ACESSO TOTAL)", fontSize = 12.sp)
                }

                Button(
                    onClick = onInjetarConfig,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("ATIVAR (INJETAR VIA SERVER)", color = DeepSpace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Spacer(Modifier.height(8.dp))
                Text("TESTE MANUAL:", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    placeholder = { Text("Cole o código aqui...", fontSize = 10.sp, color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.Gray
                    )
                )
                Button(
                    onClick = { onInjetarManual(manualText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("INJETAR TEXTO MANUAL", color = DeepSpace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (pacoteMestre == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (pacoteMestre.aplicativos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum app cadastrado.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(pacoteMestre.aplicativos) { app ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceColor, RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                            .padding(20.dp)
                    ) {
                        Text("UniTV Oficial", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Versão ${app.versao}", color = Color.Gray, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onInstalar(app, manualText) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("INSTALAR AGORA", color = DeepSpace, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}
