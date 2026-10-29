package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.ui.components.RadarAvatar
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Schermata mostrata quando si aggiunge il widget: si sceglie quale familiare mostrare. */
class MemberWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePreferences.init(this)

        // Finche' l'utente non sceglie, il widget non va aggiunto.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val members = WidgetCache.readMembers(this)

        setContent {
            MyApplicationTheme(themeMode = ThemePreferences.getThemeMode(this)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ConfigContent(
                        members = members,
                        onPick = { userId -> pick(appWidgetId, userId) },
                        onOpenApp = {
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun pick(appWidgetId: Int, userId: String) {
        val ctx = this
        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(ctx).getGlanceIdBy(appWidgetId)
                // Lega la scelta a QUESTA istanza di widget (stato per-widget di Glance).
                updateAppWidgetState(ctx, glanceId) { prefs ->
                    prefs[MemberWidget.KEY_USER_ID] = userId
                }
                // Aggiorna subito questa istanza (widget unico, ridimensionabile).
                MemberWidget().update(ctx, glanceId)
            } catch (e: Exception) {
                // in caso di errore il widget si aggiorna comunque al refresh successivo
            }
            withContext(Dispatchers.Main) {
                val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, result)
                finish()
            }
        }
    }
}

@Composable
private fun ConfigContent(
    members: List<WidgetMember>,
    onPick: (String) -> Unit,
    onOpenApp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp)
    ) {
        Text(
            "Scegli un familiare",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Il widget mostrera' la sua posizione e il suo stato.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        if (members.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Nessun dato ancora. Apri l'app almeno una volta, poi riprova ad aggiungere il widget.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onOpenApp, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Apri l'app")
                    }
                }
            }
            return
        }

        LazyColumn {
            items(members) { m ->
                MemberRow(m, onClick = { onPick(m.userId) })
            }
        }
    }
}

@Composable
private fun MemberRow(member: WidgetMember, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        RadarAvatar(name = member.name, photoBase64 = member.photoBase64, size = 44.dp)
        Text(
            member.name,
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}
