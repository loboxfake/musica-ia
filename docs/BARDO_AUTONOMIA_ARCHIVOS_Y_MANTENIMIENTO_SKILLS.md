# BARDO: autonomía de archivos del móvil y mantenimiento de skills

## Alcance previsto

BARDO podrá administrar archivos y carpetas únicamente dentro del árbol SAF que el usuario conceda expresamente, inicialmente `Descargas/BARDO` y sus subcarpetas. El acceso se realiza mediante el proveedor de documentos de Android; no se solicita acceso global al almacenamiento ni se inspeccionan carpetas de otras aplicaciones.

Operaciones previstas:
- listar y leer documentos dentro del árbol concedido;
- crear archivos y carpetas;
- mover elementos a una papelera propia de BARDO;
- eliminación permanente solo después de una confirmación expresa y vinculada al elemento exacto.

La ruta se expresa como relativa a la raíz concedida. Se rechazan rutas absolutas, segmentos `..`, separadores alternativos, controles y rutas fuera del árbol. El modelo original `Modelos/gemma-4-E2B-it.litertlm` está protegido frente a eliminación por esta política.

## Política implementada en esta rama

`BardoMobileFilePolicy` valida operaciones y rutas relativas, limita la validez temporal de las aprobaciones y vincula cada aprobación a la operación y al elemento exactos. Esta clase es una política, no un backend SAF: no abre documentos, no crea ni elimina elementos y no concede permisos Android por sí misma.

`BardoMobileFilePolicyTest` cubre traversal, rutas absolutas, operaciones rutinarias, aprobación vinculada a ruta/operación, caducidad y protección del Gemma original.

## Mantenimiento de skills

`BardoSkillMaintenancePlanner` analiza compatibilidad con la API, acciones ausentes, evidencia de pruebas y versiones posteriores habilitadas. Solo emite recomendaciones:
- conservar;
- revisar compatibilidad;
- volver a probar;
- proponer deshabilitar;
- proponer retirar una versión sustituida o rechazada.

No deshabilita ni elimina por sí mismo. La aplicación de las recomendaciones debe pasar por el gestor de skills y por la autorización correspondiente. La retirada permanente requiere aprobación humana explícita.

## Pendiente antes de considerarlo funcional

- Conectar el selector SAF a la raíz autorizada y persistir la concesión URI.
- Implementar el backend de lectura/listado/creación y papelera usando `ContentResolver` y `DocumentsContract`.
- Emitir aprobaciones de borrado únicamente desde una confirmación visible de la interfaz, consumirlas una sola vez y auditar cada operación.
- Integrar las herramientas en el catálogo y la interfaz. Las rutas `MainActivity.kt` y `BardoCoreToolCatalog.kt` están protegidas para revisión humana; esta rama no las modifica.
- Ejecutar las pruebas y compilación en Actions, generar el APK candidato y verificar su instalación y comportamiento real en Xiaomi 11T Pro.

No se declara que la gestión de archivos del móvil esté ya operativa. Los cambios actuales implementan políticas y análisis de mantenimiento, no el acceso SAF completo.
