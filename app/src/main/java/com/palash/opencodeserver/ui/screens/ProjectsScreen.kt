package com.palash.opencodeserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.palash.opencodeserver.model.Project
import com.palash.opencodeserver.ui.theme.*

@Composable
fun ProjectsScreen(
    projects: List<Project>,
    onSelectProject: (String) -> Unit,
    onOpenProjectActions: (Project) -> Unit,
    onCreateProject: (String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filteredProjects = remember(projects, searchQuery) {
        if (searchQuery.isBlank()) projects else {
            projects.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.language.contains(searchQuery, ignoreCase = true) ||
                it.path.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        containerColor = DevBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = DevAccentCyan,
                contentColor = DevBackground,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("projects_add_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Project")
            }
        },
        modifier = modifier.testTag("projects_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Search Field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DevSurface)
                    .border(1.dp, DevBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = DevTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text("Search projects...", fontSize = 13.sp, color = DevTextMuted)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedTextColor = DevTextPrimary,
                        unfocusedTextColor = DevTextPrimary
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("projects_search_input")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredProjects) { project ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DevSurface),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (project.isCurrent) DevAccentCyan.copy(alpha = 0.5f) else DevBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectProject(project.id) }
                            .testTag("project_item_${project.id}")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(DevSurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (project.isCurrent) DevAccentCyan else DevTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = project.name,
                                                style = Typography.titleMedium,
                                                color = DevTextPrimary
                                            )
                                            if (project.isCurrent) {
                                                Text(
                                                    text = "ACTIVE",
                                                    fontSize = 9.sp,
                                                    fontFamily = DevMonospace,
                                                    color = DevAccentCyan,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(DevAccentCyanSubtle)
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = project.path,
                                            fontFamily = DevMonospace,
                                            fontSize = 11.sp,
                                            color = DevTextSecondary
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onOpenProjectActions(project) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = DevTextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = project.language,
                                    fontSize = 11.sp,
                                    color = DevTextMuted
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Commit,
                                        contentDescription = null,
                                        tint = DevAccentEmerald,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = project.gitBranch,
                                        fontSize = 11.sp,
                                        fontFamily = DevMonospace,
                                        color = DevTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, path, lang ->
                onCreateProject(name, path, lang)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, path: String, lang: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("~/projects/") }
    var language by remember { mutableStateOf("TypeScript") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DevSurface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DevBorder, RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "New Project",
                    style = Typography.titleLarge,
                    color = DevTextPrimary
                )
                Text(
                    text = "Configure your local project workspace",
                    style = Typography.bodySmall,
                    color = DevTextSecondary
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        path = "~/projects/${it.lowercase().replace(" ", "-")}"
                    },
                    label = { Text("Project Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DevTextPrimary,
                        unfocusedTextColor = DevTextPrimary,
                        focusedBorderColor = DevAccentCyan,
                        unfocusedBorderColor = DevBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Directory Path") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DevTextPrimary,
                        unfocusedTextColor = DevTextPrimary,
                        focusedBorderColor = DevAccentCyan,
                        unfocusedBorderColor = DevBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text("Language / Framework") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DevTextPrimary,
                        unfocusedTextColor = DevTextPrimary,
                        focusedBorderColor = DevAccentCyan,
                        unfocusedBorderColor = DevBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = DevTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                onConfirm(name, path, language)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DevAccentCyan,
                            contentColor = DevBackground
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}
