/*
 * Copyright (c) 2010 mmazzei
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * 	FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * 	AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * 	LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * 	OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * 	THE SOFTWARE.
 */
package ar.uba.fi.pojito.xml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.jdom.Attribute;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

/**
 * Se encarga de mapear objetos a XML según distintos templates.<br />
 * 
 * @author mmazzei
 */
public class PojitoXMLConverter {
	// Delimitadores de una expresión
	private final static String START_VARIABLE_DELIM = "${";
	private final static String END_VARIABLE_DELIM = "}";

	private final static String EXPRESSION_PARTS_DELIM = ";";
	private final static String PROPERTY_DELIM = "\\.";
	private final static String ACTION_PARTS_DELIM = "=";

	// Predicados
	private final static Map<String, Predicate> defaultPredicates;
	private Map<String, Predicate> userPredicates = new HashMap<String, Predicate>();

	// Acciones
	private final static Map<String, Action> defaultActions;
	private Map<String, Action> userActions = new HashMap<String, Action>();

	/** Nombre del archivo con el template. */
	private String templateName;

	/** Datos a convertir */
	private Map<String, Object> data;

	// Estos atributos fueron necesarios para soportar la conversión de
	// colecciones. Sería muy bueno refactorizarlo para que quede más prolijo.
	/** Indica si actualmente se está procesando una colección. */
	private boolean processingCollection = false;
	/** Elemento de la colección que está siendo procesado. */
	private Object collectionElement = null;

	// Registro las acciones y predicados por defecto
	static {
		defaultPredicates = new HashMap<String, Predicate>();
		defaultPredicates.put("ifNotNull", new NotNullPredicate());
		defaultPredicates.put("ifNotEmpty", new NotEmptyCollectionPredicate());

		defaultActions = new HashMap<String, Action>();
		defaultActions.put("transform", new TransformAction());
	}

	/*-------------------------------------------------------------------------
	 *                       CONFIGURACIÓN DEL CONVERSOR
	 ------------------------------------------------------------------------*/
	/**
	 * Crea un conversor basado en el conjunto de datos pasado como parámetro.
	 */
	public PojitoXMLConverter(String templateName) {
		this.templateName = templateName;
		this.data = null;
	}

	public void setData(Map<String, Object> data) {
		this.data = data;
	}

	/**
	 * Añade un conjunto de predicados a utilizar para evaluar condiciones. Si
	 * alguno tiene el mismo nombre que uno de los predicados por defecto, lo
	 * reemplaza.
	 */
	public void addPredicates(Map<String, Predicate> predicates) {
		this.userPredicates.putAll(predicates);
	}

	public void addActions(Map<String, Action> actions) {
		this.userActions.putAll(actions);
	}

	/*-------------------------------------------------------------------------
	 *                                SERVICIO
	 ------------------------------------------------------------------------*/
	/**
	 * Genera una cadena en formato XML respetando el patrón indicado por
	 * templateName con los datos de {@link #data}.
	 * 
	 * @param templateName
	 *            Nombre del archivo con el template.
	 * @return Contenido del nuevo archivo.
	 */
	public String convert() throws JDOMException, IOException {
		Element templateRoot = getTemplateRoot(templateName);

		// Genero el árbol de salida
		// Element outputRoot = new Element(templateRoot.getName());
		Element outputRoot = processElement(templateRoot);
		copyTree(templateRoot, outputRoot);

		// Genero y escribo el documento de salida
		Document document = new Document(outputRoot);
		XMLOutputter out = new XMLOutputter(Format.getPrettyFormat());

		return out.outputString(document);
	}

	/*-------------------------------------------------------------------------
	 *                                 HELPERS
	 ------------------------------------------------------------------------*/
	/**
	 * @param templateName
	 *            Nombre del archivo de template.
	 * @return El nodo raíz del XML.
	 */
	private Element getTemplateRoot(String templateName) throws FileNotFoundException, JDOMException, IOException {
		// Obtengo la raiz del documento que contiene el template
		FileInputStream file = new FileInputStream(templateName);
		SAXBuilder parser = new SAXBuilder();
		Document doc = parser.build(file);
		Element templateRoot = doc.getRootElement();
		return templateRoot;
	}

	/**
	 * Recorre el subárbol de origen recursivamente para copiar cada elemento en
	 * el subárbol de destino.
	 * 
	 * @param templateRoot
	 *            Raíz del subárbol de origen.
	 * @param outputRoot
	 *            Raíz del subárbol de destino.
	 */
	private void copyTree(Element templateRoot, Element outputRoot) {
		for (Object child : templateRoot.getChildren()) {
			// Copio del template al destino
			Element templateChild = (Element) child;
			Element outputChild = processElement(templateChild);

			if (outputChild != null) {
				// Añado al nuevo árbol
				outputRoot.addContent(outputChild);

				// En caso de que el nodo haya mapeado sobre una collection, se
				// añaden los hijos en processElement, por lo que ya no hay que
				// seguir recorriéndolo.
				if (outputChild.getChildren().size() == 0) {
					// Sigo recorriendo
					copyTree(templateChild, outputChild);
				}
			}
		}
	}

	/**
	 * Crea un elemento idéntico al enviado por parámetro pero con las
	 * expresiones reemplazadas por el valor de la propiedad correcta del mapa.
	 * No copia los hijos.
	 * 
	 * @param templateChild
	 *            Patrón en base al cual crear el nuevo elemento.
	 * @return El nodo generado en función al template. Null si no debe
	 *         generarse ninguno.
	 */
	private Element processElement(Element templateChild) {
		// Genero un nodo con el mismo nombre que el template
		Element outputChild = new Element(templateChild.getName());

		if (!copyAttributes(templateChild, outputChild)) {
			return null;
		}
		copyValues(templateChild, outputChild);

		return outputChild;
	}

	/**
	 * Por cada elemento de la colección crea una copia (evaluando todas las
	 * expresiones sobre éste) del subárbol templateRoot en outputRoot.
	 * 
	 * @param templateRoot
	 *            Subárbol de origen.
	 * @param outputRoot
	 *            Subárbol de destino.
	 * @param collection
	 *            Conjunto de elementos a iterar.
	 */
	private void processCollection(Element templateRoot, Element outputRoot, Collection<?> collection) {
		// Indico que, a partir de aquí, las expresiones se evalúan sobre
		// #collectionElement y no sobre #data
		processingCollection = true;

		for (Object object : collection) {
			collectionElement = object;
			copyTree(templateRoot, outputRoot);
		}

		processingCollection = false;
	}

	/**
	 * Copia el texto de un elemento a otro evaluando expresiones.
	 * 
	 * @param template
	 *            Elemento de origen.
	 * @param output
	 *            Elemento destino.
	 */
	private void copyValues(Element template, Element output) {
		// Obtengo el valor evaluando expresiones
		Object value = evaluateExpression(template.getTextTrim());

		// Si es una colección, debo ejecutar un proceso más complejo
		if (value instanceof Collection<?>) {
			Collection<?> elementos = (Collection<?>) value;
			if ((elementos != null) && (elementos.size() > 0)) {
				processCollection(template, output, elementos);
			}
		}
		else {
			output.setText((value == null) ? null : value.toString());
		}
	}

	/**
	 * Copia los atributos de un elemento a otro evaluando expresiones.
	 * 
	 * @param template
	 *            Elemento desde el que se copian los atributos.
	 * @param output
	 *            Elemento al que se copian los atributos.
	 * @return <code>false</code> si algún predicado dió falso (es decir, si
	 *         debe descartarse el elemento output).
	 */
	private boolean copyAttributes(Element template, Element output) {
		// Copio cada atributo (evaluando expresiones)
		for (Object attribute : template.getAttributes()) {
			Attribute templateAttribute = (Attribute) attribute;
			Object attrValue = evaluateExpression(templateAttribute.getValue());

			// Pero, si el nodo no cumple un predicado, se lo descarta
			if (isPredicateName(templateAttribute.getName())) {
				if (!evaluatePredicate(templateAttribute.getName(), attrValue)) {
					return false;
				}
			}
			// Si el atributo no es un predicado, se lo copia
			else {
				if (attrValue != null) {
					Attribute outputAttribute = new Attribute(templateAttribute.getName(), attrValue.toString());
					output.setAttribute(outputAttribute);
				}
			}

		}

		return true;
	}

	/**
	 * Si existe un predicado definido por el usuario (ver
	 * {@link #addPredicates(Map)}), lo utiliza; si no, busca entre los
	 * predicados por defecto.
	 * 
	 * @param name
	 *            Nombre del predicado a evaluar.
	 * @param value
	 *            Objeto a evaluar.
	 * @return El resultado del predicado.
	 * @throws RuntimeException
	 *             Si no existe un predicado con el nombre indicado.
	 */
	private boolean evaluatePredicate(String name, Object value) {
		if (userPredicates.containsKey(name)) {
			return userPredicates.get(name).evaluate(value);
		}
		if (defaultPredicates.containsKey(name)) {
			return defaultPredicates.get(name).evaluate(value);
		}
		throw new RuntimeException("Intentando evaluar un predicado inexistente: " + name);
	}

	/**
	 * @return <code>true</code> si se registró un predicado con el nombre
	 *         indicado.
	 */
	private boolean isPredicateName(String name) {
		return userPredicates.containsKey(name) || defaultPredicates.containsKey(name);
	}

	/**
	 * Si existe una acción definida por el usuario (ver
	 * {@link #addActions(Map)}), la ejecuta; si no, busca entre las acciones
	 * por defecto.
	 * 
	 * @param name
	 *            Nombre de acción a ejecutar.
	 * @param param
	 *            Parámetro para ejecutar la acción.
	 * @param value
	 *            Objeto a evaluar.
	 * @return El resultado de la acción.
	 * @throws RuntimeException
	 *             Si no existe una acción con el nombre indicado.
	 */
	private Object executeAction(String name, Object param, Object value) {
		if (userActions.containsKey(name)) {
			return userActions.get(name).execute(param, value);
		}
		if (defaultActions.containsKey(name)) {
			return defaultActions.get(name).execute(param, value);
		}
		throw new RuntimeException("Intentando ejecutar una acción inexistente: " + name);
	}

	/**
	 * @param expression
	 *            El formato es: ${propiedad;accion1;accion2;...;accionN} donde:
	 *            <ul>
	 *            <li>propiedad es de la forma:
	 *            "objeto.nombrePropiedad1.nombrePropiedad2..." y significa que
	 *            debe obtenerse el valor de la propiedad 2 de la propiedad 1
	 *            del objeto del mapa {@link #data} identificado con la clave
	 *            "objeto". Deben existir los getters públicos correspondientes.
	 *            Si alguno de los datos intermedios es nulo, la expresión
	 *            resulta en null.</li>
	 *            <li>accion1 es una de las acciones soportadas por este
	 *            conversor y se aplica sobre el resultado de evaluar
	 *            "propiedad"</li>
	 *            <li>accion2 se aplica sobre el resultado de accion1</li>
	 *            </ul>
	 * @return El valor de la propiedad o dato a mostrar.
	 */
	private Object evaluateExpression(String expression) {
		// Por defecto, el resultado de la expresión es su valor literal
		Object value = expression;

		// Pero, si la cadena es un template (algo del estilo "${unaCosa}"), la
		// reemplazo por el valor adecuado
		String templateText = expression.trim();
		int strDesde = templateText.indexOf(START_VARIABLE_DELIM);
		int strHasta = templateText.indexOf(END_VARIABLE_DELIM);
		if ((strDesde >= 0) && (strHasta >= 0)) {
			// Quito ${ y }
			String cleanExpression = templateText.substring(strDesde + START_VARIABLE_DELIM.length(), strHasta
					- END_VARIABLE_DELIM.length() + 1);

			// Obtengo el valor de la propiedad
			String[] expressionParts = cleanExpression.split(EXPRESSION_PARTS_DELIM);
			value = evaluateProperty(expressionParts[0]);

			// Ejecuto la cadena de acciones sobre la misma
			for (int i = 1; i < expressionParts.length; i++) {
				String[] actionParts = expressionParts[i].trim().split(ACTION_PARTS_DELIM);
				value = executeAction(actionParts[0], actionParts[1], value);
			}
		}
		return value;
	}

	/**
	 * @param propertyExpression
	 *            Una cadena indicando la propiedad a obtener, de algún objeto
	 *            del mapa {@link #data}. El formato es:
	 *            "dataKey.property.property2".
	 * @return El valor de la propiedad.
	 */
	private Object evaluateProperty(String propertyExpression) {
		// Valor de la propiedad que se está procesando
		// Se inicializa en el objeto al que se le pedirá la primer propiedad
		Object propertyValue = getRootData();

		// Me permite saber si es una propiedad (!=0) o dato del mapa (0)
		int propertyNumber = 0;

		// Evalúo cada propiedad
		String[] propertyNames = propertyExpression.split(PROPERTY_DELIM);
		for (String propertyName : propertyNames) {
			propertyValue = getPropertyValue(propertyValue, propertyName);
			if (propertyValue == null) break;
			propertyNumber++;
		}

		return propertyValue;
	}

	/**
	 * @param object
	 *            Objeto del que se debe obtener una propiedad.
	 * @param propertyName
	 *            Nombre de la propiedad a obtener.
	 * @return Valor de la propiedad. Si es un mapa, el objeto registrado con la
	 *         clave cuyo nombre es propertyName.
	 */
	private Object getPropertyValue(Object object, String propertyName) {
		try {
			if (object == null) {
				// Do nothing
			}
			else if (object instanceof Map<?, ?>) {
				object = ((Map<?, ?>) object).get(propertyName);
			}
			else {
				// TODO (mmazzei) - Aquí debería tener en cuenta que, para
				// booleanos, es "isPropertyName"
				String getter = "get" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
				object = object.getClass().getMethod(getter).invoke(object);
			}
		}
		catch (IllegalArgumentException e) {
			throw new RuntimeException("Error al obtener la propiedad '" + propertyName
					+ "' de un objeto mientras se lo almacenaba en un XML.", e);
		}
		catch (IllegalAccessException e) {
			throw new RuntimeException("Error al obtener la propiedad '" + propertyName
					+ "' de un objeto mientras se lo almacenaba en un XML.", e);
		}
		catch (InvocationTargetException e) {
			throw new RuntimeException("Error al obtener la propiedad '" + propertyName
					+ "' de un objeto mientras se lo almacenaba en un XML.", e);
		}
		catch (SecurityException e) {
			throw new RuntimeException("Error al obtener la propiedad '" + propertyName
					+ "' de un objeto mientras se lo almacenaba en un XML.", e);
		}
		catch (NoSuchMethodException e) {
			throw new RuntimeException("Error al obtener la propiedad '" + propertyName
					+ "' de un objeto mientras se lo almacenaba en un XML.", e);
		}
		return object;
	}

	/** @return El objeto sobre el que se evaluarán las expresiones. */
	private Object getRootData() {
		return (processingCollection) ? collectionElement : data;
	}
}
