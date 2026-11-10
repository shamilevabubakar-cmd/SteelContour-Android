package ru.steelcontour.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@Entity(tableName = "projects")
data class Project(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val contractKopecks: Long)
@Entity(tableName = "operations", foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("projectId")])
data class Operation(@PrimaryKey(autoGenerate = true) val id: Long = 0, val projectId: Long, val kind: String, val amountKopecks: Long, val note: String, val createdAt: Long = System.currentTimeMillis())
@Dao interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY id DESC") fun projects(): Flow<List<Project>>
    @Query("SELECT * FROM operations ORDER BY createdAt DESC, id DESC") fun operations(): Flow<List<Operation>>
    @Insert suspend fun addProject(project: Project)
    @Insert suspend fun addOperation(operation: Operation)
    @Delete suspend fun deleteProject(project: Project)
    @Delete suspend fun deleteOperation(operation: Operation)
}
@Database(entities = [Project::class, Operation::class], version = 1, exportSchema = false)
abstract class SteelDatabase: RoomDatabase() { abstract fun dao(): ProjectDao }

private fun rub(value: Long): String = NumberFormat.getNumberInstance(Locale("ru", "RU")).format(value / 100.0) + " ₽"
private fun parseMoney(value: String): Long? {
    val number = value.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (number <= java.math.BigDecimal.ZERO) return null
    return try { number.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() } catch (_: Exception) { null }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, SteelDatabase::class.java, "steel_contour.db").build()
        setContent { MaterialTheme { SteelApp(db.dao()) } }
    }
}

@Composable fun SteelApp(dao: ProjectDao) {
    val projects by dao.projects().collectAsState(initial = emptyList())
    val operations by dao.operations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    var contract by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Материалы") }
    var error by remember { mutableStateOf("") }
    val current = projects.find { it.id == selected }
    val types = listOf("Материалы", "Зарплата", "Прочие расходы", "Платёж заказчика")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("СТАЛЬНОЙ КОНТУР", style = MaterialTheme.typography.headlineMedium)
        Text("Учёт объектов • данные на телефоне")
        Spacer(Modifier.height(12.dp))
        if (current == null) {
            val totalContracts = projects.sumOf { it.contractKopecks }
            val totalCosts = operations.filter { it.kind != "Платёж заказчика" }.sumOf { it.amountKopecks }
            Text("Договоры: ${rub(totalContracts)}")
            Text("Расходы: ${rub(totalCosts)}")
            Text("Расчётная прибыль: ${rub(totalContracts - totalCosts)}")
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Название нового объекта") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(contract, { contract = it }, label = { Text("Сумма договора, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val money = parseMoney(contract)
                if (name.isBlank() || money == null) error = "Укажите название и положительную сумму"
                else { scope.launch { dao.addProject(Project(name = name.trim(), contractKopecks = money)) }; name = ""; contract = ""; error = "" }
            }) { Text("Создать объект") }
            Text("Объекты", style = MaterialTheme.typography.titleLarge)
            LazyColumn { items(projects, key = { it.id }) { p ->
                ElevatedCard(onClick = { selected = p.id; error = "" }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) { Text(p.name); Text("Договор: ${rub(p.contractKopecks)}") }
                }
            } }
        } else {
            val own = operations.filter { it.projectId == current.id }
            val paid = own.filter { it.kind == "Платёж заказчика" }.sumOf { it.amountKopecks }
            val costs = own.filter { it.kind != "Платёж заказчика" }.sumOf { it.amountKopecks }
            TextButton(onClick = { selected = null; error = "" }) { Text("← Все объекты") }
            Text(current.name, style = MaterialTheme.typography.titleLarge)
            Text("Договор: ${rub(current.contractKopecks)}")
            Text("Получено: ${rub(paid)} • Остаток: ${rub(current.contractKopecks - paid)}")
            Text("Расходы: ${rub(costs)} • Расчётная прибыль: ${rub(current.contractKopecks - costs)}")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Новая операция")
            types.forEach { t -> Row { RadioButton(selected = kind == t, onClick = { kind = t }); Text(t, Modifier.padding(top = 12.dp)) } }
            OutlinedTextField(amount, { amount = it }, label = { Text("Сумма, ₽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Комментарий / сотрудник / материал") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val money = parseMoney(amount)
                if (money == null) error = "Введите положительную сумму"
                else { scope.launch { dao.addOperation(Operation(projectId = current.id, kind = kind, amountKopecks = money, note = note.trim())) }; amount = ""; note = ""; error = "" }
            }) { Text("Сохранить операцию") }
            Text("История операций", style = MaterialTheme.typography.titleMedium)
            LazyColumn { items(own, key = { it.id }) { op ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("${op.kind}: ${rub(op.amountKopecks)}"); if (op.note.isNotBlank()) Text(op.note) }
                    TextButton(onClick = { scope.launch { dao.deleteOperation(op) } }) { Text("Удалить") }
                }
                HorizontalDivider()
            } }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
