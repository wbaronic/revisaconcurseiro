package br.com.revisa.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.revisa.core.Grade
import br.com.revisa.data.Deck
import br.com.revisa.data.DeckReview
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF112D35)
private val Teal = Color(0xFF147D69)
private val Paper = Color(0xFFF6F5F0)
private val Muted = Color(0xFF667778)
private val Line = Color(0xFFE1E6DF)
private val Mint = Color(0xFFE4F1E9)
private val Coral = Color(0xFFB84B3D)
private val PaleCoral = Color(0xFFFCECE6)
private val Amber = Color(0xFF8A661C)
private val PaleAmber = Color(0xFFFFF3D8)
private val ScreenPadding = 22.dp
private val shape = RoundedCornerShape(22.dp)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 41.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp)
)

@Composable
fun RevisaApp(vm: RevisaViewModel) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White, secondary = Ink,
            background = Paper, onBackground = Ink, surface = Color.White, onSurface = Ink,
            surfaceVariant = Mint, onSurfaceVariant = Muted, outline = Line, error = Coral
        ),
        typography = typography
    ) {
        var exitSession by remember { mutableStateOf(false) }
        var abandonEditor by remember { mutableStateOf(false) }
        val session = vm.study
        val editor = vm.editor
        val hasBack = session != null || editor != null ||
            (vm.tab == 1 && vm.path.isNotEmpty()) ||
            (vm.tab == 2 && vm.communityUser != null)
        val goBack: () -> Unit = {
            if (!vm.busy) {
                when {
                    session != null -> if (session.complete) vm.closeStudy() else exitSession = true
                    editor != null -> abandonEditor = true
                    vm.tab == 1 -> vm.setPathDepth(vm.path.size - 1)
                    vm.tab == 2 && vm.communityPath.isNotEmpty() -> vm.setCommunityPathDepth(vm.communityPath.size - 1)
                    vm.tab == 2 -> vm.leaveCommunityUser()
                }
            }
        }
        BackHandler(enabled = vm.authenticated && hasBack, onBack = goBack)
        Scaffold(
            containerColor = Paper,
            topBar = {
                Column(Modifier.background(Paper).windowInsetsPadding(WindowInsets.statusBars)) {
                    Header(vm, hasBack, goBack)
                    if (vm.demo && vm.authenticated) {
                        Row(
                            Modifier.fillMaxWidth().background(PaleAmber).padding(horizontal = ScreenPadding, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("DEMONSTRAÇÃO LOCAL", style = MaterialTheme.typography.labelSmall, color = Amber)
                            Text("Dados neste aparelho", fontSize = 11.sp, color = Amber)
                        }
                    }
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal, trackColor = Mint)
                }
            },
            bottomBar = {
                if (vm.authenticated && session == null && editor == null) {
                    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                        listOf("Hoje", "Biblioteca", "Comunidade", "Simulados").forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = vm.tab == index, onClick = { vm.selectTab(index) }, enabled = !vm.busy,
                                icon = { LineIcon(listOf("sun", "cards", "users", "layers")[index], if (vm.tab == index) Teal else Muted) },
                                label = { Text(title) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Mint, selectedIconColor = Teal, selectedTextColor = Teal,
                                    unselectedTextColor = Muted, unselectedIconColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
                vm.error?.let { Banner(it, true, vm::dismissError) }
                vm.notice?.let { Banner(it, false, vm::dismissNotice) }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                        when {
                            vm.initializing -> Loading()
                            !vm.authenticated -> LoginScreen(vm)
                            session != null -> StudyScreen(vm, session)
                            editor != null -> EditorScreen(vm, editor)
                            vm.tab == 0 -> HomeScreen(vm)
                            vm.tab == 1 -> LibraryScreen(vm)
                            vm.tab == 2 -> CommunityScreen(vm)
                            else -> SimulationsScreen(vm)
                        }
                    }
                }
            }
        }
        if (exitSession) {
            AlertDialog(
                onDismissRequest = { exitSession = false },
                title = { Text("Encerrar esta sessão?") },
                text = { Text("As avaliações já feitas estão salvas. Ao abrir o conjunto novamente, uma nova sessão será iniciada.") },
                confirmButton = { TextButton(onClick = { exitSession = false; vm.closeStudy() }) { Text("Encerrar") } },
                dismissButton = { TextButton(onClick = { exitSession = false }) { Text("Continuar") } }
            )
        }
        if (abandonEditor) {
            AlertDialog(
                onDismissRequest = { abandonEditor = false },
                title = { Text("Descartar este rascunho?") },
                text = { Text("O conteúdo ainda não foi salvo na sua biblioteca.") },
                confirmButton = { TextButton(onClick = { abandonEditor = false; vm.closeEditor() }) { Text("Descartar") } },
                dismissButton = { TextButton(onClick = { abandonEditor = false }) { Text("Continuar editando") } }
            )
        }
    }
}

@Composable
private fun Header(vm: RevisaViewModel, hasBack: Boolean, goBack: () -> Unit) {
    var profileMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasBack && vm.authenticated) {
            TextButton(onClick = goBack, enabled = !vm.busy, contentPadding = PaddingValues(10.dp)) {
                Text("‹", fontSize = 32.sp, lineHeight = 32.sp)
            }
        } else {
            BrandMark(Modifier.padding(start = 7.dp, end = 10.dp).size(31.dp))
        }
        Text("revisa", color = Ink, fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-1).sp)
        Spacer(Modifier.weight(1f))
        if (vm.authenticated) {
            Box {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Mint)
                        .semantics { contentDescription = "Abrir minha conta" }
                        .clickable(enabled = !vm.busy) { profileMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(vm.profile?.displayName?.take(1)?.uppercase().orEmpty().ifEmpty { "R" }, fontWeight = FontWeight.Bold, color = Teal)
                }
                DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                    Text(vm.profile?.displayName ?: "Minha conta", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                    Text("@${vm.profile?.username.orEmpty()}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Muted, fontSize = 13.sp)
                    DropdownMenuItem(text = { Text("Atualizar biblioteca") }, onClick = { profileMenu = false; vm.refresh() })
                    DropdownMenuItem(text = { Text(if (vm.demo) "Sair da demonstração" else "Sair da conta") }, onClick = { profileMenu = false; vm.logout() })
                }
            }
        } else Text("APRENDA. RELEMBRE.", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(end = 7.dp))
    }
}

@Composable
private fun LoginScreen(vm: RevisaViewModel) {
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Spacer(Modifier.height(6.dp))
        Pill("UM CARTÃO. UM PASSO À FRENTE.", Mint, Teal)
        Text(if (register) "Seu próximo capítulo\ncomeça aqui." else "Conhecimento que\nfica com você.", style = MaterialTheme.typography.displaySmall)
        Text(
            "Organize seus concursos, aprenda com flashcards e revise o que precisa de mais atenção.",
            color = Muted, style = MaterialTheme.typography.bodyLarge
        )
        Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Ink)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.size(42.dp), Color(0xFFBEE8C8))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Uma rotina, duas revisões", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Conjuntos no prazo. Dificuldades em foco.", color = Color(0xFFBED0CE), fontSize = 13.sp)
                }
            }
        }
        Text(if (register) "Crie sua conta" else "Entre para continuar", style = MaterialTheme.typography.titleLarge)
        if (!vm.configured) {
            Text(
                "Esta instalação ainda precisa da conexão com o Supabase para criar contas e sincronizar estudos. Explore o app pela demonstração.",
                color = Muted, style = MaterialTheme.typography.bodyMedium
            )
            PrimaryButton("Entrar na demonstração", enabled = !vm.busy, onClick = vm::enterDemo)
        } else {
            if (register) {
                Input("Nome de usuário", username, { username = it }, enabled = !vm.busy, supporting = "De 3 a 30 letras minúsculas, números ou _")
            }
            Input("E-mail", email, { email = it }, enabled = !vm.busy, keyboard = KeyboardType.Email)
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Senha") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                enabled = !vm.busy, singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Ocultar" else "Mostrar", fontSize = 11.sp) } },
                supportingText = if (register) ({ Text("Pelo menos 8 caracteres.") }) else null
            )
            PrimaryButton(if (register) "Criar minha conta" else "Entrar", enabled = !vm.busy) {
                if (register) vm.register(email, password, username) else vm.login(email, password)
            }
            TextButton(onClick = { register = !register; vm.dismissError() }, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = !vm.busy) {
                Text(if (register) "Já tenho conta" else "Ainda não tenho conta")
            }
            HorizontalDivider(color = Line)
            OutlinedButton(
                onClick = vm::enterDemo, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp)
            ) { Text("Entrar na demonstração") }
        }
        Text("Experimente os cartões de exemplo sem cadastro.", Modifier.align(Alignment.CenterHorizontally), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun HomeScreen(vm: RevisaViewModel) {
    val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
    val reviews = vm.library.reviews.sortedBy { it.nextReviewAt }
    val due = reviews.filter { it.nextReviewAt <= System.currentTimeMillis() }
    val future = reviews.filterNot { it in due }
    val difficult = vm.library.reviewCards.count { it.score > 0 }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR"))).uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.height(8.dp))
            Text("Olá, ${vm.profile?.displayName?.substringBefore(" ") ?: "estudante"}.", style = MaterialTheme.typography.headlineLarge)
            Text("Um pouco hoje. Muito mais na memória.", color = Muted, modifier = Modifier.padding(top = 5.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("${vm.library.decks.distinctBy { it.id }.size}", "conjuntos", "cards")
                StatCard("${vm.library.decks.sumOf { it.cardCount }}", "cartões", "layers")
                StatCard("${due.size}", "no prazo", "clock")
            }
        }
        item {
            Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Ink)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Pill("REVISÃO POR CARTÃO", Color(0xFF25464A), Color(0xFFC9E2D4))
                        Spacer(Modifier.weight(1f))
                        LineIcon("spark", Color(0xFFBEE8C8), Modifier.size(23.dp))
                    }
                    Text(
                        if (difficult > 0) "$difficult ${if (difficult == 1) "cartão" else "cartões"}\npara fortalecer."
                        else "Cada erro vira\numa nova chance.",
                        color = Color.White, style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        if (difficult > 0) "Os cartões de todas as matérias, do maior para o menor número de pontos."
                        else "Os cartões com erros ou dúvidas aparecem aqui quando têm pontuação positiva.",
                        color = Color(0xFFBED0CE), style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = if (difficult > 0) vm::startGlobal else ({ vm.selectTab(1) }),
                        enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC9E8B8), contentColor = Ink),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (difficult > 0) "Revisar meus cartões  →" else "Escolher um conjunto  →") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Revisões de conjuntos", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = vm::refresh, enabled = !vm.busy) { Text("Atualizar", fontSize = 12.sp) }
            }
            Text("Primeira revisão em 7 dias. Depois, a cada 15 dias.", color = Muted, fontSize = 13.sp)
        }
        if (due.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().clip(shape).background(Color.White).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile("check", Mint, Teal)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Tudo em dia por aqui", fontWeight = FontWeight.SemiBold)
                        Text(if (reviews.isEmpty()) "Comece um conjunto para agendar sua revisão." else "Seu próximo encontro com a matéria já está marcado.", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        } else {
            items(due, key = { "due-${it.deckId}" }) { review ->
                vm.library.decks.find { it.id == review.deckId }?.let { deck ->
                    ReviewRow(deck, review, true, !vm.busy) { vm.startDeck(deck) }
                }
            }
        }
        if (future.isNotEmpty()) {
            item { Text("PRÓXIMAS REVISÕES", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 6.dp)) }
            items(future.take(5), key = { "future-${it.deckId}" }) { review ->
                vm.library.decks.find { it.id == review.deckId }?.let { deck ->
                    ReviewRow(deck, review, false, !vm.busy) { vm.startDeck(deck) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                Text("ERRO +2", color = Coral, style = MaterialTheme.typography.labelSmall)
                Text("   ·   ", color = Muted)
                Text("DÚVIDA +1", color = Amber, style = MaterialTheme.typography.labelSmall)
                Text("   ·   ", color = Muted)
                Text("ACERTO −1", color = Teal, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RowScope.StatCard(number: String, label: String, icon: String) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LineIcon(icon, Teal, Modifier.size(19.dp))
        Text(number, style = MaterialTheme.typography.headlineSmall)
        Text(label, color = Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ReviewRow(deck: Deck, review: DeckReview, due: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile("clock", if (due) PaleCoral else Mint, if (due) Coral else Teal)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(deck.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${deck.subject} · ${deck.cardCount} cartões", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${if (due) "Revisar · " else ""}${formatDate(review.nextReviewAt)}", color = if (due) Coral else Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("›", fontSize = 25.sp, color = Muted)
    }
}

@Composable
private fun LibraryScreen(vm: RevisaViewModel) {
    var delete by remember { mutableStateOf<Deck?>(null) }
    BrowseScreen(
        title = "Sua biblioteca", subtitle = "Seu conhecimento, no seu ritmo.",
        decks = vm.library.decks, path = vm.path,
        onEnter = vm::enterPath, onDepth = vm::setPathDepth,
        enabled = !vm.busy,
        headerAction = {
            PrimaryButton("+  Novo conjunto", enabled = !vm.busy, onClick = vm::newDeck)
        },
        emptyMessage = "Crie seu primeiro conjunto, importe um CSV ou encontre cartões na comunidade.",
        deckContent = { deck ->
            DeckCard(deck, !vm.busy, vm.library.reviews.find { it.deckId == deck.id },
                onStudy = { vm.startDeck(deck) }, onAdd = { vm.addCard(deck) },
                onPublic = { vm.togglePublic(deck) }, onDelete = { delete = deck })
        }
    )
    delete?.let { deck ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Excluir “${deck.title}”?") },
            text = { Text("Os cartões e o histórico deste conjunto serão removidos da sua biblioteca.") },
            confirmButton = { TextButton(onClick = { delete = null; vm.delete(deck) }) { Text("Excluir conjunto", color = Coral) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun BrowseScreen(
    title: String,
    subtitle: String,
    decks: List<Deck>,
    path: List<String>,
    onEnter: (String) -> Unit,
    onDepth: (Int) -> Unit,
    enabled: Boolean,
    headerAction: @Composable () -> Unit,
    emptyMessage: String,
    deckContent: @Composable (Deck) -> Unit
) {
    val selected = decks.filter {
        (path.getOrNull(0) == null || it.exam == path[0]) &&
            (path.getOrNull(1) == null || it.subject == path[1]) &&
            (path.getOrNull(2) == null || it.topic == path[2])
    }
    val groups = if (path.size < 3) selected.groupBy {
        when (path.size) { 0 -> it.exam; 1 -> it.subject; else -> it.topic }
    } else emptyMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, color = Muted, modifier = Modifier.padding(top = 5.dp))
        }
        item { headerAction() }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("Início", color = if (path.isEmpty()) Ink else Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onDepth(0) }.padding(vertical = 10.dp, horizontal = 5.dp))
                path.forEachIndexed { index, value ->
                    Text(" › ", color = Muted)
                    Text(value, color = if (index == path.lastIndex) Ink else Teal, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onDepth(index + 1) }.padding(vertical = 10.dp, horizontal = 5.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(listOf("CONCURSOS", "MATÉRIAS", "ASSUNTOS", "CONJUNTOS")[path.size.coerceIn(0, 3)], style = MaterialTheme.typography.labelSmall, color = Muted)
                Text("${selected.size} conjunto(s)", color = Muted, fontSize = 12.sp)
            }
        }
        if (selected.isEmpty()) {
            item { EmptyState("Espaço para aprender", emptyMessage, "cards") }
        } else if (path.size < 3) {
            items(groups.entries.sortedBy { it.key.lowercase() }, key = { it.key }) { (name, group) ->
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(Color.White).clickable(enabled = enabled) { onEnter(name) }.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(if (path.isEmpty()) "folder" else "layers", Mint, Teal)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text("${group.size} conjunto(s) · ${group.sumOf { it.cardCount }} cartões", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text("›", fontSize = 26.sp, color = Muted)
                }
            }
        } else items(selected.sortedBy { it.title }, key = { it.id }) { deck -> deckContent(deck) }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun DeckCard(deck: Deck, enabled: Boolean, review: DeckReview?, onStudy: () -> Unit, onAdd: () -> Unit, onPublic: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(if (deck.isPublic) "PÚBLICO" else "PARTICULAR", if (deck.isPublic) Mint else Paper, if (deck.isPublic) Teal else Muted)
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }, enabled = enabled, contentPadding = PaddingValues(5.dp)) { Text("•••", color = Muted, letterSpacing = 2.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Adicionar cartão") }, onClick = { menu = false; onAdd() })
                        DropdownMenuItem(text = { Text(if (deck.isPublic) "Tornar particular" else "Tornar público") }, onClick = { menu = false; onPublic() })
                        DropdownMenuItem(text = { Text("Excluir conjunto", color = Coral) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(deck.title, style = MaterialTheme.typography.titleLarge)
            Text("${deck.cardCount} cartões${review?.let { " · Revisão ${formatDate(it.nextReviewAt)}" } ?: " · Ainda não estudado"}", color = Muted, fontSize = 13.sp)
            PrimaryButton("Estudar conjunto  →", enabled = enabled && deck.cardCount > 0, onClick = onStudy)
        }
    }
}

@Composable
private fun CommunityScreen(vm: RevisaViewModel) {
    val user = vm.communityUser
    if (user != null) {
        val label = when (vm.communityPath.size) {
            0 -> "Adicionar todos os conjuntos"
            1 -> "Adicionar este concurso"
            2 -> "Adicionar esta matéria"
            else -> "Adicionar este assunto"
        }
        BrowseScreen(
            title = user.displayName, subtitle = "@${user.username} · Biblioteca pública",
            decks = vm.communityDecks, path = vm.communityPath,
            onEnter = vm::enterCommunityPath, onDepth = vm::setCommunityPathDepth,
            enabled = !vm.busy,
            headerAction = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    PrimaryButton("+  $label", enabled = !vm.busy && vm.communityDecks.isNotEmpty(), onClick = vm::copyPublic)
                    Text("Os conjuntos entram na sua biblioteca como cópias particulares, com progresso próprio.", color = Muted, fontSize = 12.sp)
                }
            },
            emptyMessage = "Este usuário ainda não compartilhou conjuntos públicos.",
            deckContent = { deck ->
                Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Pill("PÚBLICO", Mint, Teal)
                        Text(deck.title, style = MaterialTheme.typography.titleLarge)
                        Text("${deck.cardCount} cartões · ${deck.topic}", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        item {
            Pill("APRENDER TAMBÉM É COMPARTILHAR", Mint, Teal)
            Spacer(Modifier.height(14.dp))
            Text("Conhecimento\nem comunidade.", style = MaterialTheme.typography.headlineLarge)
            Text("Busque uma pessoa e explore seus concursos, matérias e assuntos públicos.", color = Muted, modifier = Modifier.padding(top = 9.dp))
        }
        item {
            OutlinedTextField(
                value = vm.userQuery, onValueChange = vm::updateQuery,
                label = { Text("Nome ou nome de usuário") },
                placeholder = { Text("Ex.: ana_estudos") },
                enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                leadingIcon = { LineIcon("search", Teal) }
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Encontrar pessoas", enabled = !vm.busy, onClick = vm::search)
        }
        if (vm.searched && vm.searchResults.isEmpty()) {
            item { EmptyState("Nenhuma pessoa encontrada", "Tente outro nome ou verifique o nome de usuário.", "users") }
        }
        if (!vm.searched) {
            item { EmptyState("Uma biblioteca de cada vez", "Você escolhe o que quer estudar. Adicione um concurso inteiro, uma matéria ou apenas um assunto.", "users") }
        }
        items(vm.searchResults, key = { it.id }) { found ->
            Row(
                Modifier.fillMaxWidth().clip(shape).background(Color.White).clickable(enabled = !vm.busy) { vm.openUser(found) }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
                    Text(found.displayName.take(1).uppercase(), color = Teal, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(found.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("@${found.username}", color = Muted, fontSize = 13.sp)
                }
                Text("›", fontSize = 26.sp, color = Teal)
            }
        }
    }
}

@Composable
private fun EditorScreen(vm: RevisaViewModel, editor: EditorState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importCsv(uri) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Text(if (editor.addToDeckId != null) "Novo cartão" else "Novo conjunto", style = MaterialTheme.typography.headlineLarge)
        Text(if (editor.addToDeckId != null) editor.title else "Dê um lugar ao que você quer aprender.", color = Muted)
        if (editor.addToDeckId == null) {
            Text("01  ORGANIZAÇÃO", style = MaterialTheme.typography.labelSmall, color = Teal, modifier = Modifier.padding(top = 7.dp))
            Input("Concurso", editor.exam, { vm.edit(editor.copy(exam = it)) }, enabled = !vm.busy)
            Input("Matéria", editor.subject, { vm.edit(editor.copy(subject = it)) }, enabled = !vm.busy)
            Input("Assunto", editor.topic, { vm.edit(editor.copy(topic = it)) }, enabled = !vm.busy)
            Input("Nome do conjunto", editor.title, { vm.edit(editor.copy(title = it)) }, enabled = !vm.busy)
            Row(Modifier.fillMaxWidth().clip(shape).background(Color.White).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (editor.isPublic) "Conjunto público" else "Conjunto particular", fontWeight = FontWeight.SemiBold)
                    Text(if (editor.isPublic) "Outras pessoas podem encontrar e copiar." else "Apenas você acessa estes cartões.", fontSize = 12.sp, color = Muted)
                }
                Switch(checked = editor.isPublic, onCheckedChange = { vm.edit(editor.copy(isPublic = it)) }, enabled = !vm.busy)
            }
            Text("02  SEUS CARTÕES", style = MaterialTheme.typography.labelSmall, color = Teal, modifier = Modifier.padding(top = 9.dp))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
                enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
            ) {
                LineIcon("upload", Teal, Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(if (editor.imported) "Escolher outro CSV" else "Importar arquivo CSV")
            }
            Text("CSV em UTF-8, até 5 MB. Pergunta e resposta em duas colunas; textos com vírgulas e quebras de linha são preservados.", color = Muted, fontSize = 12.sp)
            if (editor.cards.isNotEmpty()) {
                Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Mint)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Text("${editor.cards.size} cartões ${if (editor.imported) "importados" else "no rascunho"}", style = MaterialTheme.typography.titleMedium, color = Teal)
                        Text(if (editor.imported) "Confira os primeiros cartões antes de salvar." else "Você pode adicionar mais cartões abaixo.", color = Muted, fontSize = 12.sp)
                        editor.cards.take(3).forEachIndexed { index, card ->
                            HorizontalDivider(color = Color(0xFFCEDDD0))
                            Text("${index + 1}. ${card.front}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            Text(card.back, fontSize = 13.sp, color = Muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            if (!editor.imported) TextButton(onClick = { vm.removeDraftCard(index) }, enabled = !vm.busy, contentPadding = PaddingValues(0.dp)) { Text("Remover cartão", color = Coral, fontSize = 12.sp) }
                        }
                        if (editor.cards.size > 3) Text("+ ${editor.cards.size - 3} cartões no conjunto", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (!editor.imported || editor.addToDeckId != null) {
            Text(if (editor.cards.isEmpty()) "Escreva seu cartão" else "Próximo cartão", style = MaterialTheme.typography.titleMedium)
            Input("Pergunta / frente", editor.front, { vm.edit(editor.copy(front = it)) }, singleLine = false, minLines = 3, enabled = !vm.busy)
            Input("Resposta / verso", editor.back, { vm.edit(editor.copy(back = it)) }, singleLine = false, minLines = 4, enabled = !vm.busy)
            if (editor.addToDeckId == null) {
                TextButton(onClick = vm::appendManualCard, enabled = !vm.busy, modifier = Modifier.align(Alignment.End)) { Text("+ Adicionar mais um cartão") }
            }
        }
        PrimaryButton(if (editor.addToDeckId != null) "Salvar cartão" else "Salvar conjunto", enabled = !vm.busy, onClick = vm::saveEditor)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SimulationsScreen(vm: RevisaViewModel) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val quantities = remember { mutableStateMapOf<String, String>() }
    val available = vm.library.decks.distinctBy { it.id }.filter { it.cardCount > 0 }
    LaunchedEffect(vm.simulationSaveRevision) {
        if (submitted) {
            editing = false
            submitted = false
            editingId = null
            title = ""
            quantities.clear()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("MODELOS DE ESTUDO", style = MaterialTheme.typography.labelSmall, color = Teal)
        Text("Seus simulados", style = MaterialTheme.typography.headlineLarge)
        Text("Escolha quantos cartões entram de cada conjunto. Uma nova tentativa sorteia outros cartões e guarda sua nota.", color = Muted)
        PrimaryButton("+ Criar simulado", enabled = !vm.busy) {
            editingId = null
            title = ""
            quantities.clear()
            editing = true
        }
        if (editing) {
            Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (editingId == null) "Novo modelo" else "Editar modelo", style = MaterialTheme.typography.titleLarge)
                    Input("Nome do simulado", title, { title = it }, enabled = !vm.busy)
                    Text("Cartões por conjunto", style = MaterialTheme.typography.titleMedium)
                    if (available.isEmpty()) Text("Adicione conjuntos à sua biblioteca para criar um simulado.", color = Muted)
                    available.forEach { deck ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(deck.title, fontWeight = FontWeight.SemiBold)
                                Text("${deck.subject} · ${deck.cardCount} cartões", color = Muted, fontSize = 12.sp)
                            }
                            OutlinedTextField(
                                value = quantities[deck.id].orEmpty(),
                                onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) quantities[deck.id] = value },
                                label = { Text("Qtd.") }, singleLine = true, enabled = !vm.busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(92.dp)
                            )
                        }
                    }
                    val total = quantities.values.sumOf { it.toIntOrNull() ?: 0 }
                    Text("Total: $total cartões", color = Teal, fontWeight = FontWeight.SemiBold)
                    PrimaryButton("Salvar modelo", enabled = !vm.busy && available.isNotEmpty()) {
                        val counts = quantities.mapValues { it.value.toIntOrNull() ?: 0 }
                        val selected = counts.filterValues { it > 0 }
                        if (selected.isEmpty() || selected.any { (id, count) -> count > (available.find { it.id == id }?.cardCount ?: 0) }) {
                            vm.showError("Escolha uma quantidade válida de cartões para cada conjunto.")
                        } else {
                            submitted = true
                            vm.saveSimulation(editingId, title, counts)
                        }
                    }
                    TextButton(onClick = { editing = false; submitted = false }, enabled = !vm.busy) { Text("Cancelar") }
                }
            }
        }
        Text("Modelos salvos", style = MaterialTheme.typography.titleLarge)
        if (vm.simulations.isEmpty()) Text("Você ainda não criou um simulado.", color = Muted)
        vm.simulations.forEach { template ->
            Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(template.title, style = MaterialTheme.typography.titleMedium)
                    Text(template.packs.joinToString(" · ") { pack ->
                        val deck = available.find { it.id == pack.deckId }
                        "${pack.count} de ${deck?.title ?: "conjunto removido"}"
                    }, color = Muted, fontSize = 13.sp)
                    Button(onClick = { vm.startSimulation(checkNotNull(template.id)) }, enabled = !vm.busy) { Text("Estudar") }
                    Row {
                        TextButton(onClick = {
                            editingId = template.id
                            title = template.title
                            quantities.clear()
                            template.packs.forEach { quantities[it.deckId] = it.count.toString() }
                            editing = true
                        }, enabled = !vm.busy) { Text("Editar") }
                        TextButton(onClick = { vm.deleteSimulation(checkNotNull(template.id)) }, enabled = !vm.busy) {
                            Text("Excluir", color = Coral)
                        }
                    }
                }
            }
        }
        Text("Notas anteriores", style = MaterialTheme.typography.titleLarge)
        if (vm.simulationHistory.isEmpty()) Text("Conclua um simulado para ver sua nota aqui.", color = Muted)
        vm.simulationHistory.take(30).forEach { result ->
            Row(Modifier.fillMaxWidth().clip(shape).background(Color.White).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(result.title, fontWeight = FontWeight.SemiBold)
                    Text(result.completedAt.take(10), color = Muted, fontSize = 12.sp)
                }
                Text("${result.percentage}%", style = MaterialTheme.typography.titleLarge, color = Teal)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StudyScreen(vm: RevisaViewModel, session: StudyState) {
    if (session.complete) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(22.dp))
            Box(Modifier.size(90.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) { LineIcon("check", Teal, Modifier.size(42.dp)) }
            Pill("UM PASSO À FRENTE", Mint, Teal)
            Text("Sessão concluída.", style = MaterialTheme.typography.headlineLarge)
            Text("${session.cards.size} cartões estudados em ${session.title}.", color = Muted, style = MaterialTheme.typography.bodyLarge)
            if (session.simulationAttemptId != null) {
                Text("Nota: ${session.percentage ?: ((session.correct * 100.0 / session.cards.size).toInt())}%",
                    style = MaterialTheme.typography.headlineLarge, color = Teal)
                Text("A nota foi salva no histórico do simulado.", color = Muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ResultStat("${session.correct}", "Acertos", Teal, Mint)
                ResultStat("${session.doubts}", "Dúvidas", Amber, PaleAmber)
                ResultStat("${session.wrong}", "Erros", Coral, PaleCoral)
            }
            Card(shape = shape, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Aprender é voltar.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        session.nextReviewAt?.let { "A próxima revisão deste conjunto está marcada para ${formatDate(it)}." }
                            ?: "Os cartões com pontuação positiva continuam na revisão individual, sempre do maior para o menor número de pontos.",
                        color = Muted
                    )
                }
            }
            PrimaryButton("Voltar aos meus estudos", enabled = !vm.busy, onClick = vm::closeStudy)
        }
        return
    }
    val card = session.current ?: return
    var showTogether by rememberSaveable(card.id) { mutableStateOf(false) }
    var answerSide by rememberSaveable(card.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (answerSide && !showTogether) 180f else 0f, animationSpec = tween(450), label = "Virar cartão")
    val scroll = rememberScrollState()
    LaunchedEffect(card.id) { scroll.scrollTo(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SESSÃO DE ESTUDO", style = MaterialTheme.typography.labelSmall, color = Teal)
                Text(session.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            Text("${session.index + 1}/${session.cards.size}", color = Muted, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(progress = { session.index.toFloat() / session.cards.size }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Teal, trackColor = Line)
        Card(modifier = Modifier.fillMaxWidth().graphicsLayer { rotationY = rotation; cameraDistance = 12f * density }.clickable(enabled = !vm.busy) { if (!session.revealed) vm.reveal(); answerSide = !answerSide }, shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (answerSide && !showTogether) "RESPOSTA" else "PERGUNTA", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.graphicsLayer { rotationY = if (answerSide && !showTogether) 180f else 0f })
                    Pill("${card.score} pts", if (card.score > 0) PaleCoral else Mint, if (card.score > 0) Coral else Teal)
                }
                Box(Modifier.fillMaxWidth()) {
                    Text(card.front, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (!answerSide || showTogether) 1f else 0f })
                    Text(card.back, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (answerSide && !showTogether) 1f else 0f; rotationY = if (answerSide && !showTogether) 180f else 0f })
                }
                if (showTogether && session.revealed) Text(card.back, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (!session.revealed) {
            Text("Toque no cartão para revelar a resposta.", color = Muted)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("Errei", Grade.WRONG, PaleCoral), Triple("Dúvida", Grade.DOUBT, PaleAmber), Triple("Acertei", Grade.CORRECT, Mint)).forEach { (label, grade, tint) ->
                    Button(onClick = { vm.grade(grade) }, enabled = !vm.busy && session.pendingGrade == null, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Ink)) { Text(label, fontSize = 12.sp, maxLines = 1) }
                }
            }
            TextButton(onClick = { showTogether = !showTogether; if (!showTogether) answerSide = false }) { Text(if (showTogether) "Mostrar um lado por vez" else "Mostrar pergunta e resposta juntas") }
            if (session.pendingGrade != null) {
                Text("A avaliação ainda não foi confirmada. Tente novamente para continuar sem contar duas vezes.", color = Muted, fontSize = 13.sp)
                PrimaryButton("Tentar salvar avaliação novamente", enabled = !vm.busy) { vm.grade(session.pendingGrade) }
            }
        }
        Text("Cada cartão aparece uma vez nesta sessão. A pontuação mínima é zero; cartões com pontos positivos entram na revisão individual.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RowScope.ResultStat(value: String, label: String, color: Color, background: Color) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(background).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.headlineLarge)
        Text(label, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun GradeButton(title: String, points: String, subtitle: String, color: Color, background: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).clickable(enabled = enabled, onClick = onClick).padding(17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Text(points, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun Input(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true, singleLine: Boolean = true, minLines: Int = 1, supporting: String? = null, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        enabled = enabled, singleLine = singleLine, minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        supportingText = supporting?.let { { Text(it) } }
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(53.dp), shape = RoundedCornerShape(15.dp)) {
        Text(text, maxLines = 2)
    }
}

@Composable
private fun Pill(text: String, background: Color, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun IconTile(icon: String, background: Color, color: Color) {
    Box(Modifier.size(45.dp).clip(RoundedCornerShape(13.dp)).background(background), contentAlignment = Alignment.Center) {
        LineIcon(icon, color)
    }
}

@Composable
private fun EmptyState(title: String, body: String, icon: String) {
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        IconTile(icon, Mint, Teal)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Banner(text: String, isError: Boolean, dismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (isError) PaleCoral else Mint).padding(start = 18.dp, end = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = if (isError) Coral else Teal, fontSize = 12.sp, lineHeight = 17.sp)
        TextButton(onClick = dismiss, contentPadding = PaddingValues(5.dp)) { Text("Fechar", color = if (isError) Coral else Teal, fontSize = 11.sp) }
    }
}

@Composable
private fun Loading() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Teal, strokeWidth = 3.dp)
        Text("Preparando seus estudos…", color = Muted, modifier = Modifier.padding(top = 18.dp))
    }
}

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM", Locale.forLanguageTag("pt-BR")))

@Composable
private fun BrandMark(modifier: Modifier, color: Color = Teal) {
    Canvas(modifier) {
        val stroke = size.width * 0.075f
        rotate(-13f) {
            drawRoundRect(color.copy(alpha = .35f), Offset(size.width * .12f, size.height * .12f), Size(size.width * .64f, size.height * .78f), CornerRadius(size.width * .13f))
        }
        drawRoundRect(color, Offset(size.width * .29f, size.height * .03f), Size(size.width * .62f, size.height * .78f), CornerRadius(size.width * .13f))
        drawLine(Color.White, Offset(size.width * .44f, size.height * .31f), Offset(size.width * .74f, size.height * .31f), stroke, StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * .44f, size.height * .48f), Offset(size.width * .65f, size.height * .48f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun LineIcon(kind: String, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val unit = size.width / 24f
        val line = 1.7f * unit
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun segment(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, point(x, y), point(x2, y2), line, StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r * unit, point(x, y), style = Stroke(line))
        fun rect(x: Float, y: Float, w: Float, h: Float) =
            drawRoundRect(color, point(x, y), Size(w * unit, h * unit), CornerRadius(2 * unit), style = Stroke(line))
        when (kind) {
            "cards" -> { rect(7f, 3f, 13f, 17f); segment(4f, 7f, 4f, 20f); segment(4f, 21f, 16f, 21f); segment(10f, 8f, 17f, 8f); segment(10f, 12f, 15f, 12f) }
            "layers" -> { rect(5f, 4f, 14f, 16f); segment(8f, 8f, 16f, 8f); segment(8f, 12f, 16f, 12f); segment(8f, 16f, 13f, 16f) }
            "clock" -> { circle(12f, 12f, 8.5f); segment(12f, 7f, 12f, 12f); segment(12f, 12f, 16f, 14f) }
            "check" -> { segment(5f, 12f, 10f, 17f); segment(10f, 17f, 20f, 7f) }
            "users" -> { circle(9f, 8f, 3f); circle(18f, 9f, 2.5f); drawArc(color, 180f, 180f, false, point(2f, 14f), Size(14 * unit, 12 * unit), style = Stroke(line)); drawArc(color, 205f, 150f, false, point(14f, 15f), Size(9 * unit, 9 * unit), style = Stroke(line)) }
            "search" -> { circle(10f, 10f, 6.5f); segment(15f, 15f, 21f, 21f) }
            "folder" -> {
                val p = Path().apply { moveTo(3 * unit, 7 * unit); lineTo(3 * unit, 19 * unit); lineTo(21 * unit, 19 * unit); lineTo(21 * unit, 7 * unit); lineTo(12 * unit, 7 * unit); lineTo(10 * unit, 4 * unit); lineTo(3 * unit, 4 * unit); close() }
                drawPath(p, color, style = Stroke(line))
            }
            "upload" -> { segment(12f, 16f, 12f, 3f); segment(12f, 3f, 7f, 8f); segment(12f, 3f, 17f, 8f); segment(4f, 15f, 4f, 21f); segment(4f, 21f, 20f, 21f); segment(20f, 21f, 20f, 15f) }
            "spark" -> { segment(12f, 3f, 12f, 21f); segment(3f, 12f, 21f, 12f); segment(6f, 6f, 18f, 18f); segment(6f, 18f, 18f, 6f) }
            else -> {
                circle(12f, 12f, 4f)
                listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEach { angle ->
                    rotate(angle, point(12f, 12f)) { segment(12f, 2f, 12f, 4f) }
                }
            }
        }
    }
}
