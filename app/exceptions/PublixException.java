package exceptions;

import org.apache.http.HttpStatus;

import com.google.common.net.MediaType;

import play.mvc.Results;
import play.mvc.SimpleResult;

@SuppressWarnings("serial")
public class PublixException extends Exception {

	protected MediaType mediaType = MediaType.HTML_UTF_8;
	protected int httpStatus = HttpStatus.SC_BAD_REQUEST;;

	public PublixException(String message) {
		super(message);
	}

	public PublixException(String message, MediaType mediaType, int httpStatus) {
		super(message);
		this.mediaType = mediaType;
		this.httpStatus = httpStatus;
	}

	public PublixException(String message, MediaType mediaType) {
		super(message);
		this.mediaType = mediaType;
	}

	public PublixException(String message, int httpStatus) {
		super(message);
		this.httpStatus = httpStatus;
	}

	public SimpleResult getSimpleResult() {
		if (mediaType == MediaType.HTML_UTF_8) {
			switch (httpStatus) {
			case HttpStatus.SC_BAD_REQUEST:
				return Results.badRequest(views.html.publix.error
						.render(getMessage()));
			case HttpStatus.SC_FORBIDDEN:
				return Results.forbidden(views.html.publix.error
						.render(getMessage()));
			case HttpStatus.SC_NOT_FOUND:
				return Results
						.notFound(views.html.publix.error.render(getMessage()));
			case HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE:
				return Results.status(HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE,
						views.html.publix.error.render(getMessage()));
			default:
				return Results.internalServerError(views.html.publix.error
						.render(getMessage()));
			}
		}

		switch (httpStatus) {
		case HttpStatus.SC_BAD_REQUEST:
			return Results.badRequest(getMessage());
		case HttpStatus.SC_FORBIDDEN:
			return Results.forbidden(getMessage());
		case HttpStatus.SC_NOT_FOUND:
			return Results.notFound(getMessage());
		case HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE:
			return Results
					.status(HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE, getMessage());
		default:
			return Results.internalServerError(getMessage());
		}
	}

}