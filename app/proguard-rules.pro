// Reglas de ProGuard/R8 para el módulo :app
//
// AGP 9 exige que los archivos declarados en proguardFiles existan.
// Añade aquí reglas -keep cuando integres librerías que usen reflexión.

# Conserva los nombres de las clases del modelo (usadas por serialización/reflexión futura)
-keep class com.liora.android.data.model.** { *; }
