# BARDO: autonomía de archivos del móvil y mantenimiento de skills

## Alcance previsto

BARDO podrá administrar archivos y carpetas únicamente dentro del árbol SAF que el usuario conceda expresamente, inicialmente `Descargas/BARDO` y sus subcarpetas. El acceso se realiza mediante el proveedor de documentos de Android; no se solicita acceso global al almacenamiento ni se inspeccionan carpetas de otras aplicaciones.

Operaciones previstas:
- listar y leer documentos dentro del árbol concedido;
- crear archivos y carpetas;
- mover elementos a una papelera propia de BARDO;
- eliminación permanente solo después de una confirmación expresa y vinculada al elemento exacto.

La ruta se expresa como relativa a la raíz concedida. Se rechazan rutas absolutas, segmentos `..`, separadores alternativos, controles y rutas fuera del árbol. El modelo original `Modelos/gemma-4-E2B-it.litertlm` está protegido frente a eliminación por esta política.

## Implementación actual en esta rama

`BardoMobileFilePolicy` valida operaciones y rutas relativas, rechaza traversal y limita las operaciones al árbol SAF persistente. `BardoSharedStorage` ya implementa:
- listado de hijos directos con metadatos;
- lectura acotada de texto UTF-8 (hasta 48 KiB);
- creación y verificación de archivos de texto (hasta 28.000 caracteres);
- creación de subcarpetas bajo directorios ya existentes;
- cola persistente de solicitudes de retirada;
- traslado a `Papelera` cuando el proveedor SAF soporta MOVE;
- eliminación permanente solo de archivos o carpetas vacías y después de confirmación visible;
- registro local de las operaciones, sin guardar el contenido de los archivos.

El catálogo expone lectura/listado, creación de carpetas/archivos y solicitud de retirada. BARDO nunca recibe una herramienta que apruebe o ejecute eliminaciones. La pantalla Developer muestra ruta, motivo y naturaleza de la operación; el usuario puede aprobar o rechazar. El Gemma original sigue protegido.

`BardoMobileFilePolicyTest` cubre traversal, rutas absolutas, raíz, permisos rutinarios, aprobación vinculada a ruta/operación, caducidad y protección del Gemma original. La ejecución real SAF y compatibilidad de MOVE requieren prueba en el dispositivo.

## Mantenimiento de skills

`BardoSkillMaintenancePlanner` analiza compatibilidad con la API, acciones ausentes, evidencia de pruebas y versiones posteriores habilitadas. Solo emite recomendaciones:
- conservar;
- revisar compatibilidad;
- volver a probar;
- proponer deshabilitar;
- proponer retirar una versión sustituida o rechazada.

No deshabilita ni elimina por sí mismo. La aplicación de las recomendaciones debe pasar por el gestor de skills y por la autorización correspondiente. La retirada permanente requiere aprobación humana explícita.

## Pendiente antes de considerarlo funcional

- Verificar en el Xiaomi 11T Pro la lectura, creación, persistencia de concesión SAF y el comportamiento del proveedor al mover a Papelera.
- Ampliar lectura de documentos grandes/binarios con los módulos especializados, sin cargar modelos ni medios completos en RAM.
- Añadir pruebas instrumentadas con un DocumentProvider de prueba y pruebas de recuperación ante interrupciones.
- Ejecutar las pruebas y compilación en Actions, generar el APK candidato y verificar su instalación y comportamiento real en Xiaomi 11T Pro.

No se declara que la gestión de archivos del móvil esté ya operativa. Los cambios actuales implementan políticas y análisis de mantenimiento, no el acceso SAF completo.
