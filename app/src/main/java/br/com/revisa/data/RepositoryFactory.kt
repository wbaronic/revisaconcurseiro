package br.com.revisa.data

import android.content.Context

object RepositoryFactory {
    fun real(context: Context): RevisaRepository = SupabaseRepository(context.applicationContext)
    fun demo(context: Context): RevisaRepository = DemoRepository(context.applicationContext)
}
